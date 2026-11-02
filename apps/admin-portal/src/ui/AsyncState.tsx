import {
  Alert,
  Button,
  Loader,
  Paper,
  Stack,
  Text,
  Title,
} from '@mantine/core';
import { useId, type ReactNode } from 'react';

type StateKind = 'loading' | 'empty' | 'error' | 'conflict' | 'unavailable';
export interface AsyncStateProps {
  kind: StateKind;
  title: string;
  children?: ReactNode;
  onRetry?: () => void;
  retryLabel?: string;
}

/** A presentational outcome; callers retain authority over allowed recovery actions. */
export function AsyncState({
  kind,
  title,
  children,
  onRetry,
  retryLabel = 'Try again',
}: AsyncStateProps) {
  const id = useId();
  const content = (
    <Stack gap={8}>
      {kind === 'loading' && <Loader size="sm" aria-hidden="true" />}
      <Title order={2} size="h3" id={id}>
        {title}
      </Title>
      {children && (
        <Text component="div" c="dimmed">
          {children}
        </Text>
      )}
      {onRetry && (
        <Button onClick={onRetry} style={{ alignSelf: 'flex-start' }}>
          {retryLabel}
        </Button>
      )}
    </Stack>
  );
  return kind === 'error' || kind === 'conflict' ? (
    <Alert
      className="alert"
      role="alert"
      aria-label={title}
      variant="outline"
      color={kind === 'error' ? 'red' : 'yellow'}
    >
      {content}
    </Alert>
  ) : (
    <Paper
      component="section"
      className="card"
      role={kind === 'loading' ? 'status' : undefined}
      aria-labelledby={id}
      aria-busy={kind === 'loading'}
    >
      {content}
    </Paper>
  );
}
