import { Text } from '@mantine/core';
import { useEffect, useState } from 'react';
import { portalLogging, type PortalLogging } from './portal-logging';

const labels = {
  UNCONFIGURED: 'not configured', INVALID: 'configuration unavailable', AWAITING_SESSION: 'awaiting sign-in',
  CONNECTING: 'connecting', READY: 'connected', UNKNOWN: 'delivery unknown', CLOSED: 'stopped',
};

export function LoggingStatus({ logging = portalLogging }: { logging?: Pick<PortalLogging, 'status'> }) {
  const [status, setStatus] = useState(() => logging.status());
  useEffect(() => {
    const timer = window.setInterval(() => setStatus(logging.status()), 1000);
    return () => window.clearInterval(timer);
  }, [logging]);
  const delivery = status.delivery;
  return (
    <details aria-label="Development log delivery">
      <Text component="summary" size="xs">Development logs: {labels[status.state]}</Text>
      <Text size="xs">Pending: {delivery?.pending ?? 0}; unknown within pending: {delivery?.unknown ?? 0}.</Text>
      <Text size="xs">Confirmed in current context: {delivery?.committed ?? 0}.</Text>
      <Text size="xs">Dropped: {delivery?.dropped ?? 0}; discarded with unknown delivery: {delivery?.discardedUnknown ?? 0}.</Text>
      <Text size="xs">Discarded at context changes: {status.retiredDiscarded}; client failures: {status.failures}.</Text>
      <Text size="xs">Unknown delivery does not establish whether an event was saved.</Text>
      <Text component="a" size="xs" href="/dev/logs" target="_blank" rel="noopener noreferrer">View shared logs</Text>
    </details>
  );
}
