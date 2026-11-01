import { render, screen, within } from '@testing-library/react';
import { expect, it } from 'vitest';
import { PortalTheme } from '../../theme';
import { UploadJourney } from './UploadJourney';
it('keeps all stages unconfirmed before server evidence and marks receipt checks separately from append', () => {
  const view = render(
    <PortalTheme>
      <UploadJourney verified={false} recorded={false} />
    </PortalTheme>,
  );
  const journey = screen.getByLabelText('Upload lifecycle');
  expect(within(journey).getAllByText('Not confirmed')).toHaveLength(4);
  expect(within(journey).queryByText('Confirmed by the server')).toBeNull();
  view.rerender(
    <PortalTheme>
      <UploadJourney verified recorded={false} />
    </PortalTheme>,
  );
  expect(within(journey).getAllByText('Confirmed by the server')).toHaveLength(
    3,
  );
  expect(within(journey).getByText('Not confirmed')).toBeTruthy();
  view.rerender(
    <PortalTheme>
      <UploadJourney verified recorded />
    </PortalTheme>,
  );
  expect(within(journey).getAllByText('Confirmed by the server')).toHaveLength(
    4,
  );
  expect(within(journey).queryByText('Not confirmed')).toBeNull();
  expect(
    screen.getByText(/Processing and publication are unavailable/),
  ).toBeTruthy();
  expect(screen.queryByRole('progressbar')).toBeNull();
});
