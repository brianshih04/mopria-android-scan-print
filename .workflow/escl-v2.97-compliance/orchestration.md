# Orchestration

1. Protocol packet: schema models, capability/status parsing, negotiation, URL policy.
2. Discovery packet: TXT metadata, resource root validation, secure advertisement preference.
3. Transport packet: retries, redirect policy, status, cancellation, bounded streaming.
4. Payload packet: JPEG/PDF document mapping, common page rendering, configurable ADF limits, and Flatbed/ADF multi-page assembly.
5. Test packet: specification-shaped fixtures and negative/security cases.
6. Integration packet: reconcile APIs, update docs, run the full verification ladder.
7. Publish gate: confirm a clean intentional diff, commit once, and fast-forward `origin/main`.

No packet may copy or commit the source specification PDF. Optional eSCL features outside the declared pull-scan surface must be documented rather than represented as implemented.

All implementation packets and the local verification gate completed on 2026-08-02. The publish policy is a direct, reviewed commit to `main`, as explicitly authorized by the user; Git history is the source of truth for publication status.
