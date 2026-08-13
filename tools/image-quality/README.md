# ProShot Offline Image-Quality Harness

A deterministic, local-first Kotlin/JVM command-line tool that rejects
untrustworthy image-quality evidence, creates source-neutral reviewer packages,
seals reviewer responses before unblinding, and produces reproducible
calibration reports and locked threshold artifacts.

The tool is fully offline. It performs no network access, telemetry, uploads,
package installation, or Git invocation, and it depends only on the Kotlin/JDK
standard library and Java desktop `ImageIO` APIs (no third-party runtime
library).

## What this tool is NOT

- It does not modify the Android app, camera pipeline, processing, output, UI,
  or saved photographs.
- It does not implement sharpness, noise, motion, color-chart, PSNR, SSIM,
  LPIPS, MTF, or AI image-quality metrics. Those are
  `UNAVAILABLE_NOT_IMPLEMENTED` in this slice.
- It makes no iPhone, stock, or universal-device ranking claim.
- Five repetitions are never treated as statistically sufficient; adequacy is a
  human decision backed by evidence.

## Build and invocation

The module targets a JVM toolchain of 17 and reuses the existing Kotlin `2.1.20`
version and JUnit 4 dependency. There are no new dependencies.

```bash
./gradlew :tools:image-quality:test      # focused unit tests
./gradlew :tools:image-quality:installDist
# Unix:
./tools/image-quality/build/install/proshot-image-quality/bin/proshot-image-quality help
# Windows PowerShell:
.\tools\image-quality\build\install\proshot-image-quality\bin\proshot-image-quality.bat help
# or run directly through the application plugin:
./gradlew :tools:image-quality:run --args="help"
```

The main class is `com.proshot.tools.imagequality.MainKt`.

## Commands

Every command returns `0` only when its requested operation completes and its
binding evidence gate passes. Fail-closed schema/integrity/custody failures
return a non-zero exit code with a concise `ERROR <CODE>: message` line and no
unstructured stack trace.

Exit codes:

| Code | Meaning |
|:---|:---|
| `0` | Success |
| `1` | Usage error |
| `2` | Evidence / schema / integrity / custody failure |
| `3` | I/O error |

```text
validate          --root <dataset-dir> --out-dir <dir>
blind             --root <dataset-dir> --out-dir <package-dir> --key <key-file>
                  [--seed <hex>] [--display-max-dimension <px>]
seal-review       --package <dir> --responses <csv> --out <seal-file>
                  --reviewer <id> --category <text> --conflict NONE|DECLARED
                  [--utc-timestamp <iso-utc>]
analyze           --package <dir> --key <key-file> --root <dataset-dir> --out-dir <dir>
                  --seal <seal-file> [--seal <seal-file> ...] [--threshold <lock-file>]
                  [--utc-timestamp <iso-utc>]
lock-thresholds   --template <draft-file> --out <lock-file> [--utc-timestamp <iso-utc>]
```

`--seed` accepts 16..128 hex characters. Normal operation uses a fresh
cryptographically random seed; an explicit seed is used only for deterministic
tests and reproducibility. `--utc-timestamp` accepts a UTC ISO-8601 value and
exists so tests can pin the declared timestamps; normal operation records the
current UTC time.

## Dataset shape and strict schemas

Private datasets live under the already Git-ignored `reference-captures/` root
or outside the repository. A dataset directory contains:

- `dataset.properties`: schema version (must be `1.0`), dataset version,
  contract version, dataset kind (`CALIBRATION` or `CANDIDATE`), capture
  protocol, declared arms, required repetitions, app/baseline/candidate
  identifiers, privacy classification (`PRIVATE` or `CONTROLLED`), predeclared
  hypothesis, critical scenes, guardrails, and optional
  `allow_shared_originals=true` for a declared same-source use case.
- `trials.csv`: one row per trial with the exact columns below.
- `comparison-plan.csv`: `comparison_id, arm_a, arm_b, purpose` where purpose
  is `BLINDED_AA`, `CANDIDATE_VS_BASELINE`, `CANDIDATE_VS_STOCK`, or
  `CONTEXTUAL_REFERENCE`.
- optional `crops.csv`: `trial_id, crop_id, crop_purpose, rect_x0, rect_y0,
  rect_x1, rect_y1` with normalized `[0,1]` rectangles. Accepted crop purposes:
  `FOCUS`, `TEXTURE`, `FACE`, `HIGHLIGHT`, `SHADOW`, `ARTIFACT`, `GENERAL`.

`trials.csv` columns:

```
trial_id, scene, condition, arm, repetition, capture_order,
outcome, exclusion_reason, failure_reason,
original_path, original_hash_sha256, original_byte_size,
original_width, original_height, original_format,
review_source_path, review_source_hash_sha256, review_source_byte_size,
device, app_version, camera_identifier, output_format, output_resolution,
exif_make, exif_model, exif_orientation,
latency_ms, memory_kb, output_bytes, thermal_state, route,
fixture, focus_state, light_level, motion_state,
provenance, consent, publication_permission
```

Strict rules apply to every file:

- Files must be valid UTF-8. CSV is strict RFC-4180: quoted commas, quotes,
  CR/LF, and optional empty fields are preserved; malformed rows, unknown
  columns, missing required columns, duplicate headers, reordered headers, and
  unsupported schema versions fail closed.
- Outcome values are `SUCCESS`, `FAILED`, `EXCLUDED`. A `SUCCESS` requires the
  original and review-source file facts (path, SHA-256, byte size, dimensions,
  format); a `FAILED` trial requires a reason and forbids invented file facts;
  an `EXCLUDED` trial requires a pre-unblinding reason.
- `0` is a real measurement; it is never treated as unavailable. The strings
  `unknown`, `n/a`, and empty cells are explicit unavailable markers and are
  never converted into measurements.
- Consent values are `CONSENTED`, `NOT_APPLICABLE`, `NOT_CONSENTED`;
  provenance values are `OWNER_CAPTURED`, `PROVIDED`, `SYNTHETIC`; publication
  values are `PERMITTED`, `NOT_PERMITTED`, `PENDING`.
  `publication_permission=PERMITTED` requires `consent=CONSENTED`.

### Validation (`validate`)

The command emits `validation-summary.csv` and `validation-report.txt` and
checks at least:

- unique trial IDs and unique grains `(scene, condition, arm, repetition)`;
- accepted scene, arm, outcome, exclusion, consent, provenance, and privacy
  values for schema version `1.0`;
- required arms, scene/condition coverage, and the predeclared repetitions;
- per-trial file evidence: original and review-source SHA-256 and byte size
  must match the recorded values;
- duplicate original hashes across distinct successful trials are a critical
  integrity failure unless `allow_shared_originals=true`;
- every comparison needs exactly one eligible trial per arm per declared
  grain (and never pairs a trial with itself, never crosses repetitions, and
  never pairs two consecutive repetitions from one arm). `BLINDED_AA` is a
  two-pass design: it requires two distinct declared arm identifiers (for
  example `baseline_pass_a` and `baseline_pass_b`) that carry the same locked
  baseline build/configuration identity in the dataset metadata, and its rows
  pair exactly one eligible trial from each arm at the identical
  (scene, condition, repetition). Same-arm A/A plans are rejected with
  `AA_SAME_ARM`.
- candidate comparisons enforce one consistent candidate arm in `arm_a`
  (`CANDIDATE_VS_BASELINE` with the locked baseline in `arm_b`;
  `CANDIDATE_VS_STOCK` with stock in `arm_b`); inconsistent role assignment is
  rejected with `INCONSISTENT_ROLE`;
- crop rectangles must be finite, within `[0,1]`, positive-area, and linked to
  an existing successful trial;
- review sources must be decodable and already display-oriented;
- consent/provenance/publication contradictions fail;
- every relative data path is contained inside the real dataset root.

Any critical/high integrity failure makes the dataset ineligible for blinding
or analysis. Warnings are reported explicitly and never silently pass a binding
rule.

### Path containment

For every input file the tool: rejects absolute paths; resolves and normalizes
the path against the dataset root; requires lexical containment; obtains the
real root with `root.toRealPath()`; obtains the candidate with
`candidate.toRealPath()` using default link-following; and requires the real
candidate to remain under the real root. `NOFOLLOW_LINKS` is never used as a
substitute for escape detection.

## Blinding and the reviewer package (`blind`)

`blind` runs only after the validation evidence gate passes. It:

- uses a cryptographically random seed by default, or an explicit hex seed for
  deterministic reproduction;
- randomizes pair order and left/right placement independently;
- generates opaque package and pair IDs;
- writes the seed and the complete arm/trial mapping only to the separately
  specified private key path, refusing a key path inside the package (checked
  through the real path of the existing parents, so symlinked or junctioned
  parents cannot hide containment) and any pre-existing output;
- decodes the declared display-oriented review source with `ImageIO`, renders
  a fresh sRGB `BufferedImage`, writes whole-image and native-resolution crop
  PNGs with a fresh PNG writer and no inherited metadata, then validates the
  PNG chunk stream to accept only `IHDR`, one or more `IDAT`, and `IEND` in
  valid order. Any ancillary chunk (`tEXt`, `iTXt`, `zTXt`, `tIME`, EXIF, ICC,
  or other) fails package creation. Indexed inputs are converted to true-color
  sRGB so `PLTE` is not required;
- writes one self-contained, network-free HTML review page with a restrictive
  local Content Security Policy and no remote references;
- writes a package manifest hashing every reviewer-visible asset, the review
  page/script/style, the comparison-plan hash, the response-schema hash, and
  the private key hash;
- proves by production inspection that the package contains no arm name,
  source filename, private path, device/app identity, original hash, seed, or
  key data;
- never copies or mutates an original.

### HEIC / EXIF orientation limitation

Java `ImageIO` does not promise HEIC/HEIF or EXIF-orientation handling, so the
tool fails with `REVIEW_SOURCE_UNDECODABLE` or `ORIENTATION_NOT_NORMALIZED`
rather than rotating heuristically. A HEIC or orientation-tagged original may
use a separately prepared, display-oriented, lossless PNG review source whose
transformation, hash, and provenance are recorded. That derivative never
replaces the original.

### Offline review page and responses

The generated `review.html` uses only local `review.js`, `review.css`, and local
images. It guides reviewers through five core evaluation principles:

- **First decision:** For every pair, the reviewer must first choose which
  photograph they would keep or use (`LEFT`, `RIGHT`, or `TIE`).
- **Optional reason tags:** Reason tags represent broad analysis categories
  (such as focus, exposure, or color). Multiple tags may be selected to explain a
  preference, while detailed professional observations belong in the note.
- **Rare critical defects:** Critical defect options are reserved for rare
  cases where an image is genuinely unusable, not merely worse. `BOTH` is used
  only when the same defect renders both images unusable.
- **Export validation:** Export to `responses.csv` is blocked until every
  pair has an explicit preference choice and any dependent critical fields
  (such as selecting a defect side or providing an explanatory note for `OTHER_PREDECLARED`)
  are complete.
- **Canonical values:** The review page presents human-readable labels to the
  reviewer, but the exported `responses.csv` stores canonical machine values.

The exported UTF-8 `responses.csv` uses the standard column format:

```
package_id, pair_id, choice, reason_tags, critical_defect,
critical_defect_side, note
```

The reason tags map to canonical categories:

```
MOMENT_FOCUS, EXPOSURE_HIGHLIGHTS, COLOR_WB, SKIN_RENDERING,
TEXTURE_NOISE, NATURALNESS, VISIBLE_ARTIFACT
```

The critical-defect tags map to canonical defect categories:

```
WRONG_ORIENTATION, UNUSABLE_FOCUS_OR_MOMENT, SEVERE_SUBJECT_CLIPPING,
SEVERE_GHOSTING_OR_MERGE_ARTIFACT, OTHER_PREDECLARED
```

`critical_defect_side` accepts `LEFT`, `RIGHT`, or `BOTH`. `FAILED_SAVE` is a
capture outcome, not a reviewer-visible defect tag, and `OTHER_PREDECLARED`
requires a nonblank reviewer note.

The schema's canonical text is hashed into the package manifest; `seal-review`
rejects a package whose declared response-schema hash does not match the
generated page/parser contract. Browser rendering and CSV export receive a
manual smoke test; deterministic generation and the output schema are covered
by the JVM tests.

### The immutable review package

The package created by `blind` is immutable and contains only:

- `review.html`, `review.js`, `review.css`;
- `manifest.properties`;
- the manifest-declared files under `assets/`.

Reviewer response files and seal files must be saved **outside** the package
folder. `seal-review` and `analyze` both re-verify the complete package before
trusting it: every mandatory and derived manifest property, the
`pair.count`/`pair.order` consistency, every asset name and hash, the presence
of every declared file, and an exact physical enumeration that rejects any
additional file, missing file, renamed or changed file, symbolic link,
resolved-link escape, unexpected directory, or malformed asset name. A file
added, removed, renamed, or changed after sealing therefore makes analysis
fail closed. The private key is validated strictly (exact property set, pair
set equality with the manifest, safe repetition parsing, distinct left/right
trial ids, asset-to-trial mappings, and the manifest-recorded key hash) and
seals are validated strictly (exact properties, custody values, UTC
timestamps) with duplicate seal paths, duplicate response files, and
duplicate reviewer identities rejected (one reviewer response is never
counted twice).

## Sealing and unblinding (`seal-review`, `analyze`)

- `seal-review` verifies the complete immutable package, validates response
  completeness and allowed values, rejects duplicate or unknown pair IDs,
  hashes the package manifest and the response file, records the reviewer
  category/conflict declaration and a UTC seal timestamp, and writes an
  immutable-by-convention seal artifact. It never reads the private key.
  Missing, reordered, renamed, duplicated, or additional response columns fail
  closed.
- `analyze` accepts one or more seals plus the separate private key. It verifies
  every recorded hash (response files, package manifest, package assets, review
  page/script/style, comparison plan, key, dataset) before revealing arm
  mappings. A changed response, package, asset, plan, manifest, or key fails
  closed. The report records seal and key hashes, never key contents.

The tool cannot guarantee that a human custodian did not peek, so the custody
declaration remains a required auditable field.

## Analysis and statistics

The independent analysis unit is a captured comparison pair, not a crop and not
each reviewer vote. With one reviewer, that response is the pair outcome. With
multiple reviewers, decisive votes are aggregated by majority; equal decisive
votes or no decisive vote produce a pair-level tie. Reviewer-level raw counts
and pair-level agreement/disagreement are reported as diagnostics and are never
fed into the capture-level interval.

- decisive preference rate = wins / (wins + losses), ties excluded and tie rate
  reported separately;
- descriptive split score = (wins + 0.5 * ties) / all responses, explicitly
  non-inferential;
- Wilson 95% score interval on the decisive binary rate when defined;
- a cell with zero decisive pair outcomes reports the stable status
  `INCONCLUSIVE_ZERO_DECISIVE` and emits no `0`, `50%`, interval, or
  non-inferiority result;
- left/right and hidden-arm imbalance for A/A comparisons;
- capture statistics per arm and per scene: eligible non-excluded trials,
  capture failures, reviewer-critical successful trials, disputed trials,
  critical-failure rate, completion rate, and exclusions;
- repeated-trial median/range for available numeric diagnostics (latency,
  memory, output bytes) segmented by scene and arm;
- unavailable diagnostics stay `UNAVAILABLE`; unimplemented image metrics stay
  `UNAVAILABLE_NOT_IMPLEMENTED`.

### Candidate acceptance is binding-only

Candidate acceptance statistics use only `CANDIDATE_VS_BASELINE` pairs:

- `CANDIDATE_VS_BASELINE`: binding candidate acceptance, binding overall
  usefulness, and binding critical-scene usefulness;
- `CANDIDATE_VS_STOCK` and `CONTEXTUAL_REFERENCE`: separate contextual report
  sections only;
- `BLINDED_AA`: calibration diagnostics only.

Stock or contextual-reference outcomes never improve or reduce the candidate
PASS/FAIL/INCONCLUSIVE status. The candidate and locked-baseline arms are
identified from the validated plan and reported.

### Critical photographic failures are trial-level

For each arm, excluded trials are reported separately and excluded from the
completion and critical-failure denominators. `FAILED` capture outcomes are
automatically critical failures; `SUCCESS` trials can acquire a
reviewer-declared critical defect. The selected side is mapped through the
private key to the affected trial. Choosing `BOTH` flags both underlying trials
once for that reviewer. This is separate from the reviewer’s `LEFT`, `RIGHT`,
or `TIE` preference. Each unique reviewer contributes one trial-level boolean
(flagged when any of their responses marks that trial with a critical defect).
One reviewer's vote decides; otherwise a strict majority decides, and equal
votes leave the trial's evidence `DISPUTED`. Repeated comparisons and crops
never increase the independent trial count. A disputed binding candidate or
baseline trial makes the critical-failure gate `INCONCLUSIVE` unless another
fail-closed condition already causes `FAIL`.

### Non-inferiority semantics

- Critical failure: `candidateRate - baselineRate <= critical_failure_margin`
  passes; exceeding the margin fails.
- Repeated-capture reliability uses completion rates
  (`completionRate = SUCCESS / non-excluded trials`):
  `baselineCompletion - candidateCompletion <=
  reliability_margin_non_inferiority` passes; exceeding the margin fails. The
  same reliability check applies to every locked critical scene family; missing
  or zero-denominator evidence is `INCONCLUSIVE`. The useful-photo preference
  Wilson lower bound is never reused as "reliability".
- Candidate guardrails (latency, memory, output bytes, fallback, and
  completion/failure rates) are evaluated on candidate-arm values; baseline and
  stock values remain separately visible for context.

Output byte size is a diagnostic guardrail, not an image-quality score.

### Truthful status

- Calibration without a valid locked threshold is `INCONCLUSIVE / CALIBRATION`,
  never a quality pass.
- A candidate analysis requires a valid locked threshold; a missing or invalid
  lock fails closed.
- Android stock and optional iPhone results are separate contextual sections,
  never universal rankings or marketing claims.

## Threshold template and lock (`lock-thresholds`)

Calibration analysis writes `threshold-template.properties` bound to the exact
contract version, baseline identifier, calibration dataset/report/package/seal
hashes, and the fixed tie rule. It contains no invented values. The project
owner fills the human fields (A/A adequacy decision and justification, minimum
sample rule, critical-failure and reliability non-inferiority margins,
usefulness rule, guardrails, critical scene families, unavailable-metric
policy, approval identity/category, and UTC approval timestamp) and runs
`lock-thresholds`, which:

- re-verifies every referenced calibration artifact (dataset directory, report,
  package manifest, seals) against its bound hash before writing;
- validates every human rule: margins finite and inside `[0,1]`, positive
  minimum sample, accepted adequacy values, parseable guardrails, valid UTC
  timestamps;
- accepts only the machine-decidable usefulness grammars
  `decisive_preference_rate>=X` and `decisive_preference_lower_bound>=X` with X
  finite and inside `[0,1]`; anything else fails locking;
- validates guardrail targets by domain: rates inside `[0,1]`, latency/memory/
  output-byte limits nonnegative; unknown guardrail names fail locking;
- writes an immutable-by-hash, non-overwritable locked artifact that also binds
  a UTC lock timestamp and a self hash.

The locked artifact binds the calibration evidence; it is not expected to equal
a later candidate dataset hash. Candidate analysis:

- re-verifies every referenced calibration artifact and hash from the lock:
  unavailable or changed calibration evidence is rejected;
- verifies the current contract/baseline identity;
- requires the lock's critical-scene set to equal the candidate dataset's
  predeclared critical-scene set;
- evaluates the usefulness rule only against `CANDIDATE_VS_BASELINE` pair-level
  evidence, reports `INCONCLUSIVE_ZERO_DECISIVE` when no decisive binding pair
  exists, fails when a valid binding value misses the target, and never reports
  PASS for an unknown, unevaluated, or unavailable usefulness rule;
- refuses a threshold created or changed after the candidate response
  seal/unblinding boundary. A calibration threshold is naturally created after
  calibration review, so calibration analysis does not apply that order rule.

Ties remain excluded from the decisive preference rate, reported separately,
and counted as one half only in the descriptive split score.

### Guardrail metric names

Guardrails are comma-separated `name<op>value` entries using these accepted
metric names: `failure_rate`, `completion_rate`, `exclusion_rate`,
`critical_failure_rate`, `latency_median_ms`, `memory_median_kb`,
`output_bytes_median`, `fallback_rate`, `aa_arm_a_preference_rate`, and
`privacy_no_leak` (which is enforced to `yes` by the package-creation scan).

`--display-max-dimension` must be a positive integer. Malformed command paths,
timestamps, key or threshold numeric fields, and malformed or oversized PNG
chunk lengths fail with a concise stable error code and a non-zero exit status
without an unstructured stack trace; fatal JVM errors are never swallowed.
Directory hashing streams every file through the digest in bounded chunks and
never loads a dataset file wholly into memory.

## Privacy and private-data placement

Public code and synthetic tests live under `tools/image-quality/`. Private
originals, manifests, unblinding keys, responses, reports, and thresholds live
under the already Git-ignored `reference-captures/` root or outside the
repository. No real photograph, device serial, private path, reviewer identity,
key, report, or captured metadata enters tests, examples, documentation, or
generated public resources. Tests generate temporary synthetic `BufferedImage`
and CSV/properties fixtures only.

Every write requires an explicit output path, refuses an existing non-empty
destination, and never edits or deletes an original. The dataset root must not
be mutated between `blind` and `analyze` (the private key records the dataset
hash and `analyze` verifies it).

## Determinism

All output is deterministic for identical input plus an explicit seed, apart
from declared UTC timestamps (which can be pinned with `--utc-timestamp`). Rows
and keys are sorted before hashing or reporting; number parsing and formatting
are locale-independent; timestamps are UTC ISO-8601.
