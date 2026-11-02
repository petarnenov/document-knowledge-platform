import { Group, Stack, Text, Title } from '@mantine/core';
import type { ReactNode } from 'react';

export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string;
  description?: string;
  actions?: ReactNode;
}) {
  return (
    <Group
      className="page-heading"
      justify="space-between"
      align="flex-start"
      gap={12}
      wrap="wrap"
    >
      <Stack gap={0}>
        <Title order={1}>{title}</Title>
        {description && (
          <Text className="small" c="dimmed" maw="65ch">
            {description}
          </Text>
        )}
      </Stack>
      {actions}
    </Group>
  );
}
