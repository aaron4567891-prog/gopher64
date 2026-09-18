# Thor shader diagnostic

Experimental build, not a confirmed fix.

Adreno 740 driver 0676.53 repeatedly fails compute pipeline 26465072ca4f4aab.
The only renderer change is PARALLEL_RDP_FORCE_SYNC_SHADER=1: wait for specialized
rasterizer pipelines instead of using the asynchronous fallback. Compilation
stalls may increase. Driver and subgroup settings are unchanged.

Package io.github.gopher64.thortest (Gopher64 Thor Test) installs alongside the
official app with separate settings and saves, signed with a test key.

Install the workflow APK, select the same ROM at 1x with SSAA and CRT off, and
capture logcat. Verify 'Overriding force sync shader = 1', then check video, audio
and compute pipeline errors. An APK build alone does not verify this fix.
