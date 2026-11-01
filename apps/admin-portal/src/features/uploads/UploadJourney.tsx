/** Receipt evidence confirms both server checks; no percentage or simulated stage advances. */
export function UploadJourney({
  verified,
  recorded,
}: {
  verified: boolean;
  recorded: boolean;
}) {
  const steps = [
    ['Transfer', verified],
    ['Malware scan', verified],
    ['Structural validation', verified],
    ['Version recorded', recorded],
  ] as const;
  return (
    <div aria-label="Upload lifecycle">
      {steps.map(([label, complete], index) => (
        <div className="step" key={label}>
          <span
            className={`stepmark${complete ? ' done' : ''}`}
            aria-hidden="true"
          >
            {complete ? '✓' : index + 1}
          </span>
          <span>
            {label}
            <span className="sub">
              {complete ? 'Confirmed by the server' : 'Not confirmed'}
            </span>
          </span>
        </div>
      ))}
      <p className="hint">
        A verified receipt and a recorded version are separate outcomes.
        Processing and publication are unavailable.
      </p>
    </div>
  );
}
