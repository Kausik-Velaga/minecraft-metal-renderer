// Canopy: original MIT-licensed interpretation of public visual references.
#define SHADOWS
#define BLOOM
#define WATER_REFLECTIONS
#define CAUSTICS
#define MATERIAL_HIGHLIGHTS
#define MATERIAL_MAPS
#define PIXEL_SHADOWS
#define REFLECTION_STEPS 8 // [4 8 12]
const int shadowMapResolution = 1536; // [1024 1536 2048]
const float shadowDistance = 80.0; // [64.0 80.0 96.0]
const float shadowIntervalSize = 0.0;
const float sunPathRotation = -25.0;
/*
const int colortex0Format = RGBA16F;
const int colortex1Format = RGBA16F;
*/
const float ambientOcclusionLevel = 1.0;
