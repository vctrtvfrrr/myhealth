# Move the major version on any breaking change

ADR 0024 kept a breaking change from moving the major version before 1.0, so the 0.x line stayed. That exception also meant the pipeline could never produce 1.0.0: no commit, however breaking, led out of 0.x, and a tag pushed by hand releases nothing. The exception is removed. A breaking change moves the major version, before 1.0 and after it, with no case where it does not.

The release that declares 1.0 is therefore a dedicated `feat!:` commit that changes no behavior. It names the 25 of 25 Health Capabilities of the capability catalog as the surface the line commits to, and the pipeline computes 1.0.0 from it like any other release. Waiting for a genuinely incompatible change was rejected because the destination would then depend on something nobody plans to break, and a one-off `Release-As` footer was rejected because it would reintroduce a second way to name a version, which ADR 0024 removed by making the commits the only input.

The 1.0 line keeps the Supported Contract Range at versions 1 to 3, recommended 3. Declaring 1.0 is not a contract change, and narrowing the range only to mark a release would break an application that is still correct.

If the smoke test rejects 1.0.0, the correction is released as 1.0.1: the tag never moves and the versionCode never goes down, as ADR 0024 requires. The delivered 1.0 is the first 1.0.x that passes.

This supersedes the pre-1.0 exception in ADR 0024; the rest of that decision stands.
