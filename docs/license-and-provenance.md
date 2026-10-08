# License and provenance review

The opening review below is historical. The current distribution script
allowlists only three production JARs, configuration and documentation, excluding
all shader packs, external research checkouts, generated third-party shader
dumps, Spark and Minecraft content. This packaging check is not a new similarity audit or legal
clearance; the scope qualifications and subsequent research notes still apply.

Review date: **October 2, 2026**. Source baseline: [`406a75eb1c9b033fb7628843762ef2a833de8fb0`](https://github.com/Kausik-Velaga/minecraft-metal-renderer/tree/406a75eb1c9b033fb7628843762ef2a833de8fb0), version **0.2.0**. This review adds documentation and upstream license notices; it does not change the implementation.

**Finding:** this scoped review identified no distinctive copied implementation in the compared source corpora and no concrete reason to replace MIT for the project's own licensable material. It did identify upstream Gradle tooling that must retain its Apache license. This is an AI-assisted technical review, not an independent legal opinion or a certification that the project is plagiarism-free.

## AI authorship and the MIT license

The custom implementation, tests, and project documentation were generated entirely using Codex, under human direction and with automated and human testing. Upstream build tooling, license texts, game content, and captured evidence have separate origins, as disclosed in the [README](../README.md) and [third-party notices](../THIRD_PARTY_NOTICES.md).

MIT can remain the project's offered license for rights the contributors actually hold. It does not create copyright or grant rights owned by others. Under the U.S. Copyright Office's analysis, purely AI-generated expression is not copyrightable; protectable human contributions are assessed individually, and prompts alone do not establish authorship. This review does not assert that directing or testing this project establishes copyright in its generated implementation. Other jurisdictions may differ. See the [Copyright Office's report, executive summary and conclusion](https://www.copyright.gov/ai/Copyright-and-Artificial-Intelligence-Part-2-Copyrightability-Report.pdf).

[OpenAI's Terms of Use](https://openai.com/policies/terms-of-use/) assign OpenAI's rights, if any, in output to the user to the extent permitted by law. Those terms also explain that output may not be unique and exclude third-party output from that assignment. They are not proof of originality or a license to someone else's protected code. The existing [MIT text](../LICENSE) is preserved unchanged; its copyright notice should be read with the limited scope stated above.

## What was inspected

- The tracked source, build configuration, license declarations, reference notes, and packaged mod contents.
- All **32 Java/native implementation and test source files** under `src/` and `native/`, compared with the six corpora below. Source extensions checked were `.java`, `.mm`, `.m`, `.cpp`, `.cc`, `.c`, `.h`, `.hpp`, and `.metal`.
- Exact token sequences of at least **40 tokens**, ignoring whitespace and comments, plus a second pass of at least **80 tokens** with non-keyword identifiers normalized. Matches were extended beyond the initial window and reviewed for context. This is a heuristic source comparison, not a formal similarity or copyright determination.
- Existing attribution/license headers, the wrapper JAR checksum, and the native library's linked dependencies. Build scaffolding and documentation were reviewed separately; they were not covered by the implementation token scan.

| Comparison corpus | Revision / scope | Source files |
| --- | --- | ---: |
| [webblepebbles/MetalRender](https://github.com/webblepebbles/MetalRender/tree/6c675d1de792838770681aff9d1a7e7a54a0dae9) | 26.1 reference, `6c675d1de792838770681aff9d1a7e7a54a0dae9` | 86 |
| [CaffeineMC/Sodium](https://github.com/CaffeineMC/sodium/tree/6c26e7b7eded82ce5a1d27f9b147ce5d8de99b7a) | `mc26.2-0.9.2`, `6c26e7b7eded82ce5a1d27f9b147ce5d8de99b7a` | 653 |
| Minecraft 26.2 research extracts | Previously inspected backend/API source; a subset of the game | 211 |
| Minecraft 26.3 research extracts | Previously inspected RenderPearl/game source and Fabric gametest source; a subset of those distributions | 240 |
| [Im-Fran/MetalCraft](https://github.com/Im-Fran/MetalCraft/tree/a2cc82780d01a51d00a297f75cf07c221d7c8700) | `a2cc82780d01a51d00a297f75cf07c221d7c8700` | 34 |
| [Infatoshi/metal-mc-terrain](https://github.com/Infatoshi/metal-mc-terrain/tree/a8dbbae45a113890a2d1e2c9c3af508e722bb8fb) | `a8dbbae45a113890a2d1e2c9c3af508e722bb8fb` | 22 |

MetalCraft and metal-mc-terrain were included as additional comparison projects; inclusion in this table does not assert that their code was used to implement this mod. Research downloads and decompiled source remain ignored, untracked development material and are not published with this repository.

## Results and interpretation

The exact comparison flagged shared import lists and Mixin annotations, plus one short sequence of sampler accessors in `MetalGpuSampler.java` and MetalCraft's `MetalSampler.java`: `getMinFilter`, `getMagFilter`, `getMaxAnisotropy`, and `getMaxLod`. These are straightforward field-returning implementations of the game's sampler API. Their similarity was assessed as ordinary API boilerplate, not evidence of distinctive copied implementation. The identifier-normalized matches were import/header patterns. No native implementation match met either threshold in the inspected corpus.

MetalRender's shared/private buffers, in-flight resource slots, completion handlers, autorelease pools, and cleanup informed the research described in [upstream notes](upstream-research.md). Mojang's backends were used as behavioral references for implementing the backend API. This was therefore not a clean-room process isolated from upstream source. The comparison found no distinctive implementation overlap, but similarity thresholds cannot establish legal independence or rule out shorter, transformed, or unexamined copying.

The inspected reference licenses are materially different: [MetalRender uses the Pebbles_boon Software Licence](https://github.com/webblepebbles/MetalRender/blob/6c675d1de792838770681aff9d1a7e7a54a0dae9/LICENSE.md), [Sodium uses PolyForm Shield 1.0.0](https://github.com/CaffeineMC/sodium/blob/6c26e7b7eded82ce5a1d27f9b147ce5d8de99b7a/LICENSE.md), and [MetalCraft uses GPL-3.0](https://github.com/Im-Fran/MetalCraft/blob/a2cc82780d01a51d00a297f75cf07c221d7c8700/LICENSE). Their protected implementation cannot simply be copied here and relabeled MIT. MetalRender in particular imposes same-license and source-availability conditions on distributed derivative works. Attribution would not, by itself, resolve such a conflict.

The Gradle wrapper is genuine upstream material, with its original Apache headers and embedded license retained. Its JAR matches Gradle's published checksum. Explicit notices and a readable Apache license copy have been added. Fabric's CC0 example build setup is also credited. These are disclosed upstream components, not claimed AI-authored implementation.

## Packaged artifact

Inspected file: `minecraft-metal-renderer-0.2.0.jar`.

SHA-256: `d6e5a363ee4e2c354c8f4da23830e443f1bfb7c25a8a41edacc5656e7d8993e7`.

Its entries contain the project's classes, metadata, ARM64 native library, and MIT license. No bundled Minecraft classes/assets, other renderer implementation, Fabric API, or shaded third-party library was identified. The native library links system libraries/frameworks rather than embedding another renderer. The game supplies its shaders and rendering libraries at runtime. The Gradle wrapper is build tooling in the source repository, not part of the mod JAR.

The [Minecraft EULA](https://www.minecraft.net/en-us/eula) distinguishes original mods from redistribution of the game or substantial game content. This package inspection supports distributing the mod separately; it is not permission to distribute a modified Minecraft client or to relicense screenshots' game content.

## Limits and future changes

This review cannot prove the absence of plagiarism across all public/private code, identify the model's training sources, or establish copyright ownership. It did not scan every upstream version, all dependencies' source, patents, or all possible transformed similarities. The comparator does not analyze semantic equivalence. Short conventional code can match independently; conversely, no match is not proof of originality.

Recheck provenance and applicable terms before incorporating outside implementation or changing what is bundled. Preserve upstream authorship and required notices. If a substantive copied section is identified later, resolve its permission/license status or replace it through an appropriately independent implementation; a disclaimer or renaming variables is not a remedy.

## Shader loader 0.1.0 addition

The loader was developed using this project's renderer, Minecraft/Fabric interfaces, public
shader-pack format documentation, and local inspection of the user-supplied BSL 10.1.8 pack.
No competing loader or optimization-mod implementation source was inspected for this addition.
This statement concerns the new loader work; it does not replace the historical renderer review
above. The loader's code and tests were AI-generated under human direction.

BSL is separately authored and is not relicensed by this project. Its source, textures, ZIP,
and generated translations are excluded from both the repository and release artifacts. Players
obtain their own pack and the loader processes it locally. The validation screenshots show actual
game output; they are not copies of pack source or assets. The initial support target is one
pack version at its default options, not a certification of general compatibility or legal status.

## October 3, 2026 source-research addition

The user subsequently authorized implementation inspection to record optimization approaches,
without copying code. The [source-research register](performance-source-review.md) records selected
paths in nine projects, their exact revisions, license checks and the resulting prose observations.
This includes Iris shader-runtime implementation, so the earlier statement about no competing
loader source applies to the historical 0.1.0 addition, not to all subsequent research.

This pass changed documentation only. External code remains ignored research material and is not
incorporated into production or release artifacts. Current Sodium and Entity Culling implementations,
and C2ME's proprietary OpenCL subtree, were excluded from this pass. Some inspected files reference
other upstream authors/licenses, as recorded in the research notes. This was source-informed
research, not a clean-room process; no new similarity scan or legal clearance is claimed. Future
implementation needs its own provenance review if protected upstream expression is incorporated.
