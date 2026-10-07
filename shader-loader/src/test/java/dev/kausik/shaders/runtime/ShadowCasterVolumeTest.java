package dev.kausik.shaders.runtime;

import java.util.Random;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4d;

/** CPU geometry invariants for receiver-based culling, independent of a running client or GPU. */
public final class ShadowCasterVolumeTest {
  public static void main(String[] args) {
    Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70), 1.643f, 0.05f, 1024);
    verifyOffCameraCaster(projection);
    Random random = new Random(0x5ca57eL);
    int samples = 0;
    for (int camera = 0; camera < 12; camera++) {
      // Affine translation/rotation/stretch cover walking bob, hurt tilt and nausea conventions.
      Matrix4f view = new Matrix4f()
          .translate(0.18f, -0.13f, 0)
          .rotateZ(0.07f)
          .scale(1.04f, 0.96f, 1)
          .rotateX((float) Math.toRadians(camera * 13 - 65))
          .rotateY((float) Math.toRadians(camera * 31));
      for (Vector3f light : new Vector3f[] {
          new Vector3f(0, 1, 0), new Vector3f(0, -1, 0),
          new Vector3f(1, 0, 0), new Vector3f(-1, 0, 0),
          new Vector3f(0, 0, 1), new Vector3f(0, 0, -1),
          new Vector3f(1, 0.000001f, -0.3f).normalize(),
          new Vector3f(-0.3f, -0.000001f, 1).normalize(),
          new Vector3f(0.2f, 0.766f, 0.642f).normalize()}) {
        ShadowCasterVolume volume = ShadowCasterVolume.create(projection, view, light, 64);
        if (volume.planeCount() == 0) throw new AssertionError("Valid camera disabled culling");
        Matrix4d inverse = new Matrix4d(projection).mul(new Matrix4d(view)).invert();
        for (int i = 0; i < 200; i++) {
          double nx = i < 8 ? ((i & 1) == 0 ? -1 : 1) : random.nextDouble() * 2 - 1;
          double ny = i < 8 ? ((i & 2) == 0 ? -1 : 1) : random.nextDouble() * 2 - 1;
          double nz = i < 8 ? ((i & 4) == 0 ? -1 : 1) : random.nextDouble() * 2 - 1;
          Vector4d receiver = inverse.transform(new Vector4d(nx, ny, nz, 1));
          receiver.div(receiver.w);
          double travel = random.nextDouble() * 4096;
          Vector3f offset = new Vector3f(random.nextFloat() * 2 - 1,
              random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1).normalize(63.99f);
          double x = receiver.x + light.x * travel + offset.x;
          double y = receiver.y + light.y * travel + offset.y;
          double z = receiver.z + light.z * travel + offset.z;
          if (!volume.intersects(x - 0.01, y - 0.01, z - 0.01, x + 0.01, y + 0.01, z + 0.01))
            throw new AssertionError("Rejected a padded lightward caster, camera=" + camera + ", light=" + light);
          samples++;
        }
        Vector4d eye = new Matrix4d(view).invert().transform(new Vector4d(0, 0, 0, 1));
        if (!volume.intersects(eye.x, eye.y, eye.z, eye.x, eye.y, eye.z))
          throw new AssertionError("Excluded near-eye volumetric/hand receiver");
      }
    }
    verifyUsefulRejection(projection);
    verifyInvalidInputs(projection);
    ShadowReceiverPaddingTest.run();
    System.out.println("PASS: " + samples + " padded lightward caster samples, off-camera occluders, camera effects, useful section rejection and safe invalid-input fallback");
  }

  private static void verifyOffCameraCaster(Matrix4f projection) {
    ShadowCasterVolume volume = ShadowCasterVolume.create(projection, new Matrix4f(), new Vector3f(0, 1, 0), 0);
    // Receiver (0,0,-20) is visible; this occluder is far above the camera frustum.
    if (!volume.intersects(-1, 79, -21, 1, 81, -19))
      throw new AssertionError("Off-camera sunward caster was culled");
    if (volume.intersects(-1, -81, -21, 1, -79, -19))
      throw new AssertionError("Sweep went away from the light");
    if (volume.intersects(-1, 79, 19, 1, 81, 21))
      throw new AssertionError("Irrelevant caster behind the receiver view was retained");
  }

  private static void verifyUsefulRejection(Matrix4f projection) {
    ShadowCasterVolume volume = ShadowCasterVolume.create(projection, new Matrix4f(), new Vector3f(0, 1, 0), 64);
    int candidates = 0, rejected = 0;
    for (int x = -256; x < 256; x += 16) {
      for (int y = -128; y < 128; y += 16) {
        for (int z = -256; z < 256; z += 16) {
          candidates++;
          if (!volume.intersects(x, y, z, x + 16, y + 16, z + 16)) rejected++;
        }
      }
    }
    if (rejected < candidates / 4)
      throw new AssertionError("64-block margin prevented useful rejection: " + rejected + "/" + candidates);
    // AABB tests must keep a box crossing a receiver boundary even if its center is outside.
    if (!volume.intersects(-80, -80, -20, 80, 80, -10))
      throw new AssertionError("Boundary-crossing section was culled");
  }

  private static void verifyInvalidInputs(Matrix4f projection) {
    for (ShadowCasterVolume volume : new ShadowCasterVolume[] {
        ShadowCasterVolume.create(new Matrix4f().zero(), new Matrix4f(), new Vector3f(0, 1, 0), 64),
        ShadowCasterVolume.create(projection, new Matrix4f(), new Vector3f(), 64),
        ShadowCasterVolume.create(projection, new Matrix4f(), new Vector3f(0, 1, 0), Double.NaN)}) {
      if (!volume.intersects(10000, 10000, 10000, 10016, 10016, 10016))
        throw new AssertionError("Invalid input rejected geometry");
    }
  }

}
