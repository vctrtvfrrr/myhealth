# Preserve unknown envelope properties inside the digest

An envelope property this contract version cannot interpret is carried into the canonical rendering at every level instead of being dropped, and therefore enters the content digest. Accepting an item and discarding part of it would answer `200`, let the application clear it from the outbox, and lose observed content for good, which is the opposite of what ADR 0002 exists to guarantee.

Preservation outside the digest was rejected: two observations differing only by an unknown property would then collapse onto one Observed Record Version through the uniqueness constraint, and the richer one would be discarded anyway. Rejecting unknown properties outright was also rejected, because it removes the additive tolerance that lets the two sides be released at different moments. The accepted cost is that an additive field makes a new Observed Record Version of the same Health Record — the same rule `sourcePayload` already follows, and faithful for the same reason: what was reported was different.

The batch root is excluded on purpose. It is transport framing rather than something the source observed, so it belongs to no observation to be preserved in.

An item carrying nothing unknown renders exactly as it did before, so no stored digest changes.
