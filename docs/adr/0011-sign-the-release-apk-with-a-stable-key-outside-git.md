# Sign the release APK with a stable key outside Git

The application is installed and updated by hand, without a store, so the CI pipeline signs every release APK with the same private key and publishes it as a build artifact. The key is what preserves the application identity: Android refuses an update signed by a different key, and the only way through would be an uninstall, which erases the outbox and the local operational state the update was meant to keep.

The keystore is never committed. It reaches the build as `MYHEALTH_RELEASE_*` environment variables, provisioned on the runner from repository secrets into a path outside the workspace and removed when the job ends. The release variant fails with the download instruction when any of them is missing, instead of falling back to the debug key or to an unsigned APK: both would install today and refuse the next update, which is the failure this check exists to prevent.

Neither a store listing nor a Play App Signing key is used, because the Data Owner is the only installer and adding an external signing authority to a private pipeline would give one more party control over the identity that guards local data.
