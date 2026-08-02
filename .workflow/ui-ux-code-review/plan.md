# UI/UX Redesign and Code Review

Goal: redesign the Android app so scan and print flows feel modern, clear, and easy to operate, then complete a repository-wide code review and fix material findings.

## Success criteria

- Home presents scan and print as the two unmistakable primary actions.
- A user can start scan or file printing in at most three intentional taps from launch.
- Device, integration mode, busy, empty, success, and error states explain the next action.
- Scan settings are compact, readable, persisted, and safe for Flatbed and ADF.
- Core controls have semantic labels and Material touch targets; layouts tolerate larger text.
- Domain, network, storage, lifecycle, and print code receive a full review; high/medium findings are fixed or explicitly documented.
- Debug build, lint, unit tests, and emulator smoke tests pass without fatal exceptions.

## Constraints

- Preserve Mock and Real integration behavior and the existing `sim` baseline branch.
- Keep Android Print Framework as the print transport boundary and eSCL as the scan protocol.
- Do not claim physical-device compatibility without physical-device evidence.
- Avoid unrelated dependency or architecture migrations during this pass.

## Work packets

1. UX and Compose audit: information architecture, visual hierarchy, state feedback, accessibility.
2. Domain and integration audit: ViewModel state, eSCL HTTP/protocol, discovery, cancellation, error handling.
3. Document pipeline audit: export, PDF/image adapters, URI handling, resource cleanup.
4. Implementation: design system and primary screens.
5. Verification: tests, lint, build, emulator, final review report.

## Integration policy

All implementation is performed in this working tree. Existing behavior is preserved unless a review finding proves it unsafe or confusing. Changes are verified before the workflow is marked complete.

