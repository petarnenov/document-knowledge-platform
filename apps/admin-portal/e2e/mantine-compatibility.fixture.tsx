// Compile-only regression fixture for the declaration-only repair tracked by #124.
import { CloseButton, FileInput, NativeSelect, TextInput } from '@mantine/core';
import { useSet } from '@mantine/hooks';

export function MantineCompatibilityFixture() {
  const values = useSet<number>([1]);
  const first: number | undefined = values.values().next().value;
  values.add(2);
  return (
    <>
      <TextInput label="Omitted" />
      <TextInput label="Boolean" disabled={false} />
      <TextInput
        label="Explicit undefined"
        disabled={undefined}
        required={undefined}
        id={undefined}
      />
      <TextInput label="Native values" required={true} id="fixture" />
      <NativeSelect data={['One']} disabled={undefined} />
      <FileInput disabled={undefined} />
      <CloseButton aria-label="Close" />
      <CloseButton aria-label="Close" disabled={true} />
      <CloseButton aria-label="Close" disabled={undefined} />
      <span>{first}</span>
      {/* @ts-expect-error required must remain boolean or undefined */}
      <TextInput required="yes" />
      {/* @ts-expect-error id must remain string or undefined */}
      <TextInput id={123} />
      {/* @ts-expect-error disabled must remain boolean or undefined */}
      <TextInput disabled="yes" />
      {/* @ts-expect-error close button disabled must remain boolean or undefined */}
      <CloseButton disabled={1} />
      {/* @ts-expect-error representative controlled file value must remain a File */}
      <FileInput value="not-a-file" />
      {/* @ts-expect-error representative select data must remain strings or option objects */}
      <NativeSelect data={[123]} />
    </>
  );
}
