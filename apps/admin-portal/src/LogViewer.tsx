import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Alert,
  Badge,
  Box,
  Button,
  Group,
  NativeSelect,
  Paper,
  Switch,
  Table,
  Text,
  TextInput,
  Title,
  useMantineColorScheme,
} from '@mantine/core';
import { PortalTheme } from './theme';
import { ServerState } from './query';
import {
  LogReadError,
  readLogs,
  type LogFilters,
  type StoredLog,
} from './log-api';

export function LogViewer() {
  return (
    <PortalTheme>
      <ServerState>
        <LogWorkspace />
      </ServerState>
    </PortalTheme>
  );
}

function LogRow({ row }: { row: StoredLog }) {
  const event = row.event;
  return (
    <Table.Tr>
      <Table.Td>
        <time dateTime={row.receivedAt}>
          {new Date(row.receivedAt).toLocaleString()}
        </time>
      </Table.Td>
      <Table.Td>
        <Badge
          className="log-severity"
          variant="light"
          color={
            event.severity === 'ERROR' || event.severity === 'FATAL'
              ? 'red'
              : event.severity === 'WARN'
                ? 'yellow'
                : 'blue'
          }
        >
          {event.severity}
        </Badge>
      </Table.Td>
      <Table.Td>
        <Text size="sm" fw={600}>
          {event.component}
        </Text>
        <Text size="xs" c="dimmed">
          {event.eventName}
        </Text>
      </Table.Td>
      <Table.Td>
        <code>{event.buildId}</code>
        <Text size="xs" c="dimmed">
          Run: {event.runId}
        </Text>
      </Table.Td>
      <Table.Td>
        <details>
          <summary>Details</summary>
          <Text size="xs">
            Event time:{' '}
            <time dateTime={event.timestamp}>{event.timestamp}</time>
          </Text>
          <Text size="xs">Correlation: {event.correlationId}</Text>
          <pre className="log-details">{JSON.stringify(row, null, 2)}</pre>
        </details>
      </Table.Td>
    </Table.Tr>
  );
}

function LogWorkspace() {
  const { colorScheme, setColorScheme } = useMantineColorScheme();
  const [buildId, setBuildId] = useState('');
  const [component, setComponent] = useState('');
  const [severity, setSeverity] = useState('');
  function filters(): LogFilters {
    const now = Date.now();
    return {
      from: new Date(now - 86_400_000).toISOString(),
      to: new Date(now).toISOString(),
      buildId: buildId.trim(),
      component: component.trim(),
      minSeverity: severity,
    };
  }
  const [applied, setApplied] = useState<LogFilters>(filters);
  const query = useQuery({
    queryKey: ['local-dev-logs', applied],
    queryFn: ({ signal }) => readLogs(applied, signal),
    retry: false,
    refetchOnWindowFocus: false,
  });
  const nextCursor = query.data?.nextCursor;
  function refresh(event?: FormEvent) {
    event?.preventDefault();
    setApplied(filters());
  }
  return (
    <Box className="shell log-shell">
      <a className="skip-link" href="#main-content">
        Skip to content
      </a>
      <Group component="header" className="topbar">
        <a className="brand" href="/dev/logs">
          <span className="brand-mark" aria-hidden="true">
            D
          </span>
          <span>
            Document Knowledge Platform
            <span className="brand-caption">Development workspace</span>
          </span>
        </a>
        <Group gap="sm">
          <Badge variant="outline">Local · read only</Badge>
          <Switch
            label="Light theme"
            checked={colorScheme === 'light'}
            onChange={(event) =>
              setColorScheme(event.currentTarget.checked ? 'light' : 'dark')
            }
          />
        </Group>
      </Group>
      <div className="workspace">
        <Box component="aside" className="sidebar">
          <p className="nav-label">Development</p>
          <nav aria-label="Development navigation">
            <a href="/dev/logs" aria-current="page">
              Log explorer
            </a>
          </nav>
          <p className="sidebar-note">
            Shared records across builds.
            <br />
            Filter by execution and component.
          </p>
        </Box>
        <main id="main-content" tabIndex={-1}>
          <p className="eyebrow">DKP / DEVELOPMENT</p>
          <Group justify="space-between" align="flex-start">
            <div>
              <Title order={1}>Development logs</Title>
              <Text c="dimmed" mt="xs">
                Browse persisted events from the shared log store.
              </Text>
            </div>
            <Button
              variant="default"
              onClick={() => refresh()}
              disabled={query.isFetching}
            >
              Refresh
            </Button>
          </Group>
          <Group className="log-overview" grow>
            <Paper withBorder p="md">
              <Text size="xs" c="dimmed">
                Records on this page
              </Text>
              <Text size="xl" fw={700}>
                {query.data ? query.data.events.length : '—'}
              </Text>
            </Paper>
            <Paper withBorder p="md">
              <Text size="xs" c="dimmed">
                Received time window
              </Text>
              <Text fw={600}>Last 24 hours</Text>
            </Paper>
            <Paper withBorder p="md">
              <Text size="xs" c="dimmed">
                Read status
              </Text>
              <Text fw={600}>
                {query.isFetching
                  ? 'Loading'
                  : query.isError
                    ? 'Unavailable'
                    : query.data
                      ? 'Connected'
                      : 'Waiting'}
              </Text>
            </Paper>
          </Group>
          <Paper
            component="form"
            withBorder
            p="md"
            onSubmit={refresh}
            className="log-filters"
          >
            <TextInput
              label="Build ID"
              placeholder="All builds"
              value={buildId}
              onChange={(event) => setBuildId(event.currentTarget.value)}
              maxLength={36}
            />
            <TextInput
              label="Component"
              placeholder="All components"
              value={component}
              onChange={(event) => setComponent(event.currentTarget.value)}
              maxLength={96}
            />
            <NativeSelect
              label="Minimum severity"
              value={severity}
              onChange={(event) => setSeverity(event.currentTarget.value)}
              data={[
                '',
                'TRACE',
                'DEBUG',
                'INFO',
                'WARN',
                'ERROR',
                'FATAL',
              ].map((value) => ({ value, label: value || 'All severities' }))}
            />
            <Button type="submit" disabled={query.isFetching}>
              Apply filters
            </Button>
          </Paper>
          <Text size="xs" c="dimmed" mb="sm">
            Received at is the server storage time. Event time is supplied by
            the emitting component.
          </Text>
          <div aria-live="polite" aria-busy={query.isFetching}>
            {query.isFetching && (
              <Text role="status">Loading persisted records…</Text>
            )}
            {query.isError && (
              <Alert
                color="red"
                title={
                  query.error instanceof LogReadError &&
                  query.error.invalidQuery
                    ? 'Invalid filters'
                    : 'Unable to read logs'
                }
              >
                {query.error instanceof LogReadError
                  ? query.error.message
                  : 'Log records are unavailable. Try refreshing.'}
              </Alert>
            )}
            {!query.isFetching &&
              !query.isError &&
              query.data?.events.length === 0 && (
                <Paper withBorder p="xl">
                  <Title order={2}>No records in this window</Title>
                  <Text c="dimmed" mt="sm">
                    Try another filter or refresh after new events have been
                    recorded.
                  </Text>
                </Paper>
              )}
            {!query.isError && query.data && query.data.events.length > 0 && (
              <Paper withBorder className="log-table">
                <Table.ScrollContainer minWidth={900}>
                  <Table striped highlightOnHover>
                    <Table.Thead>
                      <Table.Tr>
                        {[
                          'Received at',
                          'Severity',
                          'Component / event',
                          'Build / run',
                          'Stored details',
                        ].map((title) => (
                          <Table.Th key={title}>{title}</Table.Th>
                        ))}
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {query.data.events.map((row) => (
                        <LogRow
                          key={row.producerId + row.scopeId + row.event.eventId}
                          row={row}
                        />
                      ))}
                    </Table.Tbody>
                  </Table>
                </Table.ScrollContainer>
              </Paper>
            )}
          </div>
          {nextCursor && !query.isError && (
            <Group justify="flex-end" mt="md">
              <Button
                variant="default"
                disabled={query.isFetching}
                onClick={() => setApplied({ ...applied, cursor: nextCursor })}
              >
                Next page
              </Button>
            </Group>
          )}
        </main>
      </div>
      <footer>Development diagnostics · Shared persistent store</footer>
    </Box>
  );
}
