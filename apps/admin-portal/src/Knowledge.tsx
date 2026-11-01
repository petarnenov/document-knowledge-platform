import {
  Alert,
  Button,
  Group,
  NativeSelect,
  NumberInput,
  Paper,
  Stack,
  Text,
  Textarea,
  TextInput,
  Title,
} from '@mantine/core';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getSession, type SessionView } from './identity-api';
import { sessionKey } from './session-key';
import {
  evaluateAudience,
  inspectAudience,
  inspectCollection,
  inspectCollectionAcl,
  knowledgeActions,
  knowledgeCommand,
  knowledgeError,
  KnowledgeError,
  listCollections,
  listMembers,
  snapshotPolicy,
  validatePolicy,
  type AclChange,
  type AudienceRule,
  type KnowledgeCommand,
} from './knowledge-api';

export function Knowledge() {
  const session = useQuery({
    queryKey: sessionKey,
    queryFn: ({ signal }) => getSession(signal),
  });
  if (session.isPending) return <p role="status">Checking your session…</p>;
  if (!session.data || session.error)
    return (
      <section>
        <Title order={1}>Knowledge collections</Title>
        <p role="alert">Sign in and choose a tenant to manage knowledge.</p>
        <Link to="/identity">Workspace access</Link>
      </section>
    );
  if (!session.data.selectedTenant)
    return (
      <section>
        <Title order={1}>Knowledge collections</Title>
        <p>Choose a tenant before opening collections.</p>
        <Link to="/identity">Workspace access</Link>
      </section>
    );
  return (
    <KnowledgeWorkspace
      key={[
        session.data.issuer,
        session.data.subject,
        session.data.selectedTenant,
        session.data.csrfToken,
      ].join('|')}
      session={session.data}
    />
  );
}
function KnowledgeWorkspace({ session }: { session: SessionView }) {
  const client = useQueryClient();
  const scope = [
    'knowledge',
    session.issuer,
    session.subject,
    session.selectedTenant,
    session.csrfToken,
  ] as const;
  const scopeText = JSON.stringify(scope);
  const owner = useRef(new AbortController());
  const retry = useRef<{ body: string; key: string } | null>(null);
  useEffect(() => {
    owner.current = new AbortController();
    const owned = owner.current;
    void client.cancelQueries({
      predicate: (q) =>
        q.queryKey[0] === 'knowledge' &&
        JSON.stringify(q.queryKey.slice(0, 5)) !== scopeText,
    });
    client.removeQueries({
      predicate: (q) =>
        q.queryKey[0] === 'knowledge' &&
        JSON.stringify(q.queryKey.slice(0, 5)) !== scopeText,
    });
    return () => {
      owned.abort();
      void client.cancelQueries({
        queryKey: JSON.parse(scopeText) as unknown[],
      });
      client.removeQueries({ queryKey: JSON.parse(scopeText) as unknown[] });
      for (const mutation of client
        .getMutationCache()
        .findAll({ mutationKey: JSON.parse(scopeText) as unknown[] }))
        client.getMutationCache().remove(mutation);
    };
  }, [client, scopeText]);
  const [cursor, setCursor] = useState<string>();
  const [selected, setSelected] = useState<string | null>(null);
  const [memberCursor, setMemberCursor] = useState<string>();
  const [panel, setPanel] = useState<
    'metadata' | 'acl' | 'audience' | 'members'
  >('metadata');
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [reason, setReason] = useState('');
  const [mode, setMode] = useState<'RESTRICT' | 'DELEGATE'>('RESTRICT');
  const [changes, setChanges] = useState<AclChange[]>([]);
  const [rules, setRules] = useState<AudienceRule[]>([]);
  const [documentId, setDocumentId] = useState('');
  const [documentRevision, setDocumentRevision] = useState<number | string>(0);
  const [snapshot, setSnapshot] = useState<{
    snapshotToken: string;
    documentId: string;
    documentRevision: number;
    decision: string;
  } | null>(null);
  const [blocked, setBlocked] = useState(false);
  const [denialError, setDenialError] = useState<unknown>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [decision, setDecision] = useState<string | null>(null);
  const page = useQuery({
    queryKey: [...scope, 'list', cursor ?? ''],
    queryFn: ({ signal }) => listCollections(signal, cursor),
    enabled: !blocked,
  });
  const detail = useQuery({
    queryKey: [...scope, 'detail', selected],
    queryFn: ({ signal }) => inspectCollection(selected!, signal),
    enabled: selected !== null && !blocked,
  });
  const acl = useQuery({
    queryKey: [...scope, 'acl', selected],
    queryFn: ({ signal }) => inspectCollectionAcl(selected!, signal),
    enabled: selected !== null && panel === 'acl' && !blocked,
  });
  const audience = useQuery({
    queryKey: [...scope, 'audience', selected],
    queryFn: ({ signal }) => inspectAudience(selected!, signal),
    enabled: selected !== null && panel === 'audience' && !blocked,
  });
  const members = useQuery({
    queryKey: [...scope, 'members', selected, memberCursor ?? ''],
    queryFn: ({ signal }) => listMembers(selected!, signal, memberCursor),
    enabled: selected !== null && panel === 'members' && !blocked,
  });
  type Command = {
    kind: KnowledgeCommand;
    body: object;
    membershipId?: string;
  };
  const intent = useRef<{
    command: Command;
    collectionId: string | undefined;
    key: string;
  } | null>(null);
  const [uncertain, setUncertain] = useState(false);
  function invalidate() {
    // Old cursors describe the prior authority/fence view and must never be replayed after a command.
    void client.cancelQueries({ queryKey: scope });
    setCursor(undefined);
    setMemberCursor(undefined);
    setSnapshot(null);
    setDecision(null);
    void client.invalidateQueries({ queryKey: scope, refetchType: 'none' });
    void client.refetchQueries({
      queryKey: scope,
      type: 'active',
      predicate: (query) =>
        !['list', 'members'].includes(String(query.queryKey[5])) ||
        query.queryKey.at(-1) === '',
    });
  }
  function refuse(error: unknown) {
    setCursor(undefined);
    setMemberCursor(undefined);
    setSnapshot(null);
    setDecision(null);
    setDenialError(error);
    setUncertain(
      error instanceof KnowledgeError &&
        error.status === 503 &&
        intent.current !== null,
    );
    if (
      error instanceof KnowledgeError &&
      [401, 403, 404, 409, 503].includes(error.status)
    ) {
      setBlocked(true);
      void client.cancelQueries({ queryKey: scope });
      client.removeQueries({ queryKey: scope });
      if (error.status !== 409) {
        setSelected(null);
        setChanges([]);
        setRules([]);
        setTitle('');
        setDescription('');
      }
      if (error.status === 401) client.setQueryData(sessionKey, null);
    }
  }
  const mutation = useMutation({
    mutationKey: [...scope, 'command'],
    mutationFn: async (command: Command) => {
      const body = JSON.stringify({ selected, ...command });
      if (retry.current?.body !== body)
        retry.current = { body, key: crypto.randomUUID() };
      intent.current = {
        command,
        collectionId: selected ?? undefined,
        key: retry.current.key,
      };
      return knowledgeCommand(
        command.kind,
        session,
        owner.current.signal,
        command.body,
        retry.current.key,
        selected ?? undefined,
        command.membershipId,
      );
    },
    onSuccess: () => {
      retry.current = null;
      intent.current = null;
      setUncertain(false);
      setNotice(
        'The command was accepted. Access is checked again when viewing results.',
      );
      invalidate();
    },
    onError: refuse,
  });
  const recovery = useMutation({
    mutationKey: [...scope, 'recover'],
    mutationFn: async () => {
      const original = intent.current;
      if (!original) throw new KnowledgeError(400, 'BAD_REQUEST');
      return knowledgeCommand(
        original.command.kind,
        session,
        owner.current.signal,
        original.command.body,
        original.key,
        original.collectionId,
        original.command.membershipId,
      );
    },
    onSuccess: () => {
      intent.current = null;
      retry.current = null;
      setUncertain(false);
      setNotice(
        'The original command was confirmed. Refresh to check current access.',
      );
      setDenialError(null);
    },
    onError: refuse,
  });
  const evaluation = useMutation({
    mutationKey: [...scope, 'evaluation'],
    mutationFn: () =>
      evaluateAudience(selected!, session, owner.current.signal),
    onSuccess: (result) =>
      setDecision(
        `Your audience decision is ${result.decision}. This does not grant document access.`,
      ),
    onError: refuse,
  });
  const policy = useMutation({
    mutationKey: [...scope, 'policy'],
    mutationFn: async (kind: 'snapshot' | 'validate') => {
      const revision = revisionValue(documentRevision);
      if (!Number.isSafeInteger(revision) || revision < 0)
        throw new KnowledgeError(400, 'BAD_REQUEST');
      if (kind === 'validate') {
        if (
          !snapshot ||
          snapshot.documentId !== documentId ||
          snapshot.documentRevision !== revision
        )
          throw new KnowledgeError(400, 'BAD_REQUEST');
        return validatePolicy(
          documentId,
          revision,
          snapshot.snapshotToken,
          session,
          owner.current.signal,
        );
      }
      const body = JSON.stringify({ kind, documentId, revision });
      if (retry.current?.body !== body)
        retry.current = { body, key: crypto.randomUUID() };
      const result = await snapshotPolicy(
        documentId,
        revision,
        session,
        owner.current.signal,
        retry.current.key,
      );
      retry.current = null;
      setSnapshot(result);
      return result;
    },
    onSuccess: (result) =>
      setDecision(
        `Complete policy decision: ${result.decision}. Compatibility does not grant read access or publication readiness.`,
      ),
    onError: refuse,
  });
  const busy =
    mutation.isPending ||
    recovery.isPending ||
    evaluation.isPending ||
    policy.isPending;
  const current = detail.isSuccess && !detail.error ? detail.data : undefined;
  const error =
    denialError ??
    mutation.error ??
    recovery.error ??
    evaluation.error ??
    policy.error ??
    page.error ??
    detail.error ??
    acl.error ??
    audience.error ??
    members.error;
  useEffect(
    () =>
      client.getQueryCache().subscribe((event) => {
        if (
          event.type !== 'updated' ||
          event.query.state.status !== 'error' ||
          JSON.stringify(event.query.queryKey.slice(0, 5)) !== scopeText
        )
          return;
        const failure = event.query.state.error;
        setCursor(undefined);
        setMemberCursor(undefined);
        setDenialError(failure);
        setBlocked(true);
        setSelected(null);
        setSnapshot(null);
        setDecision(null);
        setChanges([]);
        setRules([]);
        setTitle('');
        setDescription('');
        void client.cancelQueries({
          queryKey: JSON.parse(scopeText) as unknown[],
        });
        client.removeQueries({ queryKey: JSON.parse(scopeText) as unknown[] });
        if (failure instanceof KnowledgeError && failure.status === 401)
          client.setQueryData(sessionKey, null);
      }),
    [client, scopeText],
  );
  function submit(event: FormEvent, kind: KnowledgeCommand, body: object) {
    event.preventDefault();
    setNotice(null);
    setDecision(null);
    mutation.mutate({ kind, body });
  }
  function select(id: string) {
    setSelected(id);
    setMemberCursor(undefined);
    setPanel('metadata');
    setChanges([]);
    setRules([]);
    setSnapshot(null);
    setNotice(null);
    setDecision(null);
    mutation.reset();
    recovery.reset();
    evaluation.reset();
    policy.reset();
  }
  function refresh() {
    setCursor(undefined);
    setMemberCursor(undefined);
    setBlocked(false);
    setDenialError(null);
    setNotice(null);
    mutation.reset();
    recovery.reset();
    evaluation.reset();
    policy.reset();
    invalidate();
  }
  return (
    <section>
      <p className="eyebrow">DKP / KNOWLEDGE</p>
      <Title order={1}>Knowledge collections</Title>
      <Text>
        Selected tenant: {session.selectedTenant}. Collection policy and
        document access are independently checked.
      </Text>
      {error && (
        <Alert color="red" role="alert">
          {knowledgeError(error)}
        </Alert>
      )}
      {notice && <p role="status">{notice}</p>}
      {decision && <p role="status">{decision}</p>}
      {uncertain && (
        <Button disabled={busy} onClick={() => recovery.mutate()}>
          Retry uncertain command with original key
        </Button>
      )}
      <Button onClick={refresh} disabled={busy}>
        Refresh knowledge
      </Button>
      <Paper
        component="section"
        className="info-card"
        aria-labelledby="create-collection"
      >
        <Title order={2} id="create-collection">
          Create collection
        </Title>
        <form
          onSubmit={(event) => submit(event, 'create', { title, description })}
        >
          <TextInput
            label="New collection title"
            value={title}
            onChange={(e) => setTitle(e.currentTarget.value)}
            required
            maxLength={256}
          />
          <Textarea
            label="New collection description"
            value={description}
            onChange={(e) => setDescription(e.currentTarget.value)}
            maxLength={4096}
          />
          <Button type="submit" disabled={busy || blocked || !title}>
            Create collection
          </Button>
        </form>
      </Paper>
      <Paper
        component="section"
        className="info-card"
        aria-label="Accessible collections"
      >
        <Title order={2}>Accessible collections</Title>
        {page.isPending && <p role="status">Loading collections…</p>}
        {!blocked && page.isSuccess && !page.error && (
          <>
            <ul>
              {page.data.items.map((item) => (
                <li key={item.collectionId}>
                  <Button
                    variant="subtle"
                    onClick={() => select(item.collectionId)}
                  >
                    {item.metadata.title}
                  </Button>
                  <Text span> — {item.status}</Text>
                </li>
              ))}
            </ul>
            {page.data.items.length === 0 && (
              <p>No accessible collections on this page.</p>
            )}
            <Group>
              <Button
                disabled={busy || !cursor}
                onClick={() => setCursor(undefined)}
              >
                First collections page
              </Button>
              <Button
                disabled={busy || !page.data.nextCursor}
                onClick={() => setCursor(page.data.nextCursor ?? undefined)}
              >
                Next collections page
              </Button>
            </Group>
          </>
        )}
      </Paper>
      {selected && detail.isPending && (
        <p role="status">Checking collection access…</p>
      )}
      {current && (
        <Paper
          component="section"
          className="info-card"
          aria-label="Selected collection"
        >
          <Title order={2}>{current.metadata.title}</Title>
          <Text>{current.metadata.description}</Text>
          <Text>
            Status: {current.status}; revision {current.revision};
            classification ceiling: {current.classificationCeiling}.
          </Text>
          <Group>
            {(['metadata', 'acl', 'audience', 'members'] as const).map(
              (value) => (
                <Button
                  key={value}
                  variant={panel === value ? 'filled' : 'outline'}
                  onClick={() => {
                    setPanel(value);
                    setDecision(null);
                  }}
                >
                  {
                    {
                      metadata: 'Collection settings',
                      acl: 'Collection ACL',
                      audience: 'Audience policy',
                      members: 'Memberships',
                    }[value]
                  }
                </Button>
              ),
            )}
          </Group>
          <Textarea
            label="Change reason"
            value={reason}
            onChange={(e) => setReason(e.currentTarget.value)}
            maxLength={1024}
          />
          {panel === 'metadata' && (
            <>
              <form
                onSubmit={(event) =>
                  submit(event, 'metadata', {
                    expectedRevision: current.revision,
                    metadata: { title, description },
                  })
                }
              >
                <TextInput
                  label="Updated collection title"
                  value={title}
                  onChange={(e) => setTitle(e.currentTarget.value)}
                  required
                  maxLength={256}
                />
                <Textarea
                  label="Updated collection description"
                  value={description}
                  onChange={(e) => setDescription(e.currentTarget.value)}
                  maxLength={4096}
                />
                <Button
                  type="button"
                  onClick={() => {
                    setTitle(current.metadata.title);
                    setDescription(current.metadata.description);
                  }}
                >
                  Load current metadata
                </Button>
                <Button type="submit" disabled={busy || blocked || !title}>
                  Save collection metadata
                </Button>
              </form>
              <Button
                disabled={busy || !reason}
                onClick={() =>
                  mutation.mutate({
                    kind: 'status',
                    body: {
                      expectedRevision: current.revision,
                      status:
                        current.status === 'ACTIVE' ? 'ARCHIVED' : 'ACTIVE',
                      reason,
                    },
                  })
                }
              >
                {current.status === 'ACTIVE'
                  ? 'Archive collection'
                  : 'Unarchive collection'}
              </Button>
            </>
          )}
          {panel === 'acl' && (
            <>
              <Title order={3}>Current collection ACL</Title>
              {acl.isPending && <p role="status">Loading ACL…</p>}
              {acl.isSuccess && !acl.error && (
                <ul>
                  {acl.data.rules.map((rule, i) => (
                    <li key={i}>
                      {rule.principal.subject} at {rule.principal.issuer}:{' '}
                      {rule.effect} {rule.action}
                    </li>
                  ))}
                </ul>
              )}
              <NativeSelect
                label="ACL change mode"
                data={['RESTRICT', 'DELEGATE']}
                value={mode}
                onChange={(e) => setMode(e.currentTarget.value as typeof mode)}
              />
              <AclEditor
                changes={changes}
                setChanges={setChanges}
                session={session}
              />
              <Button
                disabled={
                  busy ||
                  !reason ||
                  changes.length === 0 ||
                  !acl.isSuccess ||
                  Boolean(acl.error)
                }
                onClick={() =>
                  mutation.mutate({
                    kind: 'acl',
                    body: {
                      expectedRevision: current.revision,
                      mode,
                      changes,
                      reason,
                    },
                  })
                }
              >
                Apply ACL changes
              </Button>
            </>
          )}
          {panel === 'audience' && (
            <>
              <Title order={3}>Audience policy</Title>
              {audience.isPending && (
                <p role="status">Loading audience policy…</p>
              )}
              {audience.isSuccess && !audience.error && (
                <>
                  <Text>
                    Policy revision {audience.data.revision}; empty ALLOW rules
                    deny all.
                  </Text>
                  <ul>
                    {audience.data.rules.map((rule, i) => (
                      <li key={i}>
                        {rule.effect} {selectorLabel(rule)}
                      </li>
                    ))}
                  </ul>
                  <Button onClick={() => setRules(audience.data.rules)}>
                    Edit current audience rules
                  </Button>
                </>
              )}
              <AudienceEditor
                rules={rules}
                setRules={setRules}
                session={session}
              />
              <Button
                disabled={
                  busy ||
                  !reason ||
                  !audience.isSuccess ||
                  Boolean(audience.error)
                }
                onClick={() =>
                  mutation.mutate({
                    kind: 'audience',
                    body: { expectedRevision: current.revision, rules, reason },
                  })
                }
              >
                Replace audience policy
              </Button>
              <Button disabled={busy} onClick={() => evaluation.mutate()}>
                Evaluate my audience access
              </Button>
            </>
          )}
          {panel === 'members' && (
            <>
              <Title order={3}>Collection memberships</Title>
              <Text>
                Only currently shareable documents are listed; hidden members
                are not counted.
              </Text>
              {members.isPending && <p role="status">Loading memberships…</p>}
              {members.isSuccess && !members.error && (
                <>
                  <ul>
                    {members.data.items.map((member) => (
                      <li key={member.membershipId}>
                        <Text>
                          {member.documentId} — {member.state}; membership
                          revision {member.revision}
                        </Text>
                        <Button
                          disabled={busy || !reason}
                          onClick={() =>
                            mutation.mutate({
                              kind: 'membership',
                              membershipId: member.membershipId,
                              body: {
                                expectedRevision: current.revision,
                                expectedMembershipRevision: member.revision,
                                expectedDocumentRevision:
                                  revisionValue(documentRevision),
                                state:
                                  member.state === 'ACTIVE'
                                    ? 'INACTIVE'
                                    : 'ACTIVE',
                                reason,
                              },
                            })
                          }
                        >
                          {member.state === 'ACTIVE'
                            ? 'Deactivate'
                            : 'Activate'}{' '}
                          membership {member.documentId}
                        </Button>
                      </li>
                    ))}
                  </ul>
                  {members.data.items.length === 0 && (
                    <p>No accessible memberships on this page.</p>
                  )}
                  <Group>
                    <Button
                      disabled={busy || !memberCursor}
                      onClick={() => setMemberCursor(undefined)}
                    >
                      First memberships page
                    </Button>
                    <Button
                      disabled={busy || !members.data.nextCursor}
                      onClick={() =>
                        setMemberCursor(members.data.nextCursor ?? undefined)
                      }
                    >
                      Next memberships page
                    </Button>
                  </Group>
                </>
              )}
              <TextInput
                label="Document ID to attach"
                value={documentId}
                onChange={(e) => {
                  setDocumentId(e.currentTarget.value);
                  setSnapshot(null);
                }}
              />
              <NumberInput
                label="Current document revision"
                value={documentRevision}
                onChange={setDocumentRevision}
                min={0}
                max={Number.MAX_SAFE_INTEGER}
                allowDecimal={false}
              />
              <Button
                disabled={busy || !reason || !documentId}
                onClick={() =>
                  mutation.mutate({
                    kind: 'attach',
                    body: {
                      expectedRevision: current.revision,
                      documentId,
                      expectedDocumentRevision: revisionValue(documentRevision),
                      sharingScope: 'SELECTED_TENANT',
                      reason,
                    },
                  })
                }
              >
                Attach document
              </Button>
              <Text>
                Enter the current document revision before changing membership
                state. ACTIVE retries are checked against current document
                authority.
              </Text>
            </>
          )}
        </Paper>
      )}
      <Paper
        component="section"
        className="info-card"
        aria-labelledby="document-policy"
      >
        <Title order={2} id="document-policy">
          Complete document policy
        </Title>
        <Text>
          Evaluate every collection scope for this document. Hidden scopes
          cannot be selectively omitted.
        </Text>
        <TextInput
          label="Policy document ID"
          value={documentId}
          onChange={(e) => {
            setDocumentId(e.currentTarget.value);
            setSnapshot(null);
            setDecision(null);
          }}
        />
        <NumberInput
          label="Policy document revision"
          value={documentRevision}
          onChange={(value) => {
            setDocumentRevision(value);
            setSnapshot(null);
            setDecision(null);
          }}
          min={0}
          max={Number.MAX_SAFE_INTEGER}
          allowDecimal={false}
        />
        <Group>
          <Button
            disabled={busy || blocked || !documentId}
            onClick={() => policy.mutate('snapshot')}
          >
            Create policy snapshot
          </Button>
          <Button
            disabled={busy || !snapshot}
            onClick={() => policy.mutate('validate')}
          >
            Validate current policy snapshot
          </Button>
        </Group>
        <Text>
          The private snapshot reference stays in this view and is cleared on
          access, identity or tenant changes.
        </Text>
      </Paper>
    </section>
  );
}
function revisionValue(value: number | string) {
  return typeof value === 'string' && value.trim() === ''
    ? Number.NaN
    : Number(value);
}
function AclEditor({
  changes,
  setChanges,
  session,
}: {
  changes: AclChange[];
  setChanges: (value: AclChange[]) => void;
  session: SessionView;
}) {
  const [issuer, setIssuer] = useState(session.issuer);
  const [subject, setSubject] = useState(session.subject);
  const [action, setAction] = useState<(typeof knowledgeActions)[number]>(
    'knowledge.collection.inspect',
  );
  const [operation, setOperation] =
    useState<AclChange['operation']>('REMOVE_ALLOW');
  return (
    <Stack>
      <TextInput
        label="ACL target issuer"
        value={issuer}
        onChange={(e) => setIssuer(e.currentTarget.value)}
      />
      <TextInput
        label="ACL target subject"
        value={subject}
        onChange={(e) => setSubject(e.currentTarget.value)}
      />
      <NativeSelect
        label="Knowledge action"
        data={[...knowledgeActions]}
        value={action}
        onChange={(e) => setAction(e.currentTarget.value as typeof action)}
      />
      <NativeSelect
        label="ACL operation"
        data={['ADD_ALLOW', 'REMOVE_ALLOW', 'ADD_DENY', 'REMOVE_DENY']}
        value={operation}
        onChange={(e) =>
          setOperation(e.currentTarget.value as typeof operation)
        }
      />
      <Button
        disabled={!issuer || !subject || changes.length >= 100}
        onClick={() =>
          setChanges([
            ...changes,
            { operation, principal: { issuer, subject }, action },
          ])
        }
      >
        Add ACL change
      </Button>
      <ol>
        {changes.map((entry, i) => (
          <li key={i}>
            {entry.operation} {entry.action} for {entry.principal.subject}
            <Button
              variant="subtle"
              aria-label={`Remove ACL change ${i + 1}`}
              onClick={() =>
                setChanges(changes.filter((_, index) => index !== i))
              }
            >
              Remove
            </Button>
          </li>
        ))}
      </ol>
    </Stack>
  );
}
function selectorLabel(rule: AudienceRule) {
  const selector = rule.selector;
  switch (selector.kind) {
    case 'PRINCIPAL':
      return `${selector.principal.subject} at ${selector.principal.issuer}`;
    case 'TENANT':
      return `tenant ${selector.tenant}`;
    case 'FIRM':
      return `firm ${selector.firmId} at ${selector.issuer}`;
    case 'RESOURCE_SCOPE':
      return `resource scope ${selector.resourceScopeId} at ${selector.issuer}`;
  }
}
function AudienceEditor({
  rules,
  setRules,
  session,
}: {
  rules: AudienceRule[];
  setRules: (value: AudienceRule[]) => void;
  session: SessionView;
}) {
  const [kind, setKind] =
    useState<AudienceRule['selector']['kind']>('PRINCIPAL');
  const [effect, setEffect] = useState<AudienceRule['effect']>('ALLOW');
  const [issuer, setIssuer] = useState(session.issuer);
  const [value, setValue] = useState(session.subject);
  function add() {
    const selector: AudienceRule['selector'] =
      kind === 'PRINCIPAL'
        ? { kind, principal: { issuer, subject: value } }
        : kind === 'TENANT'
          ? { kind, tenant: value }
          : kind === 'FIRM'
            ? { kind, issuer, firmId: value }
            : { kind, issuer, resourceScopeId: value };
    setRules([...rules, { effect, selector }]);
  }
  return (
    <Stack>
      <NativeSelect
        label="Audience rule effect"
        data={['ALLOW', 'DENY']}
        value={effect}
        onChange={(e) => setEffect(e.currentTarget.value as typeof effect)}
      />
      <NativeSelect
        label="Audience selector kind"
        data={['PRINCIPAL', 'TENANT', 'FIRM', 'RESOURCE_SCOPE']}
        value={kind}
        onChange={(e) => {
          setKind(e.currentTarget.value as typeof kind);
          setValue('');
        }}
      />
      {kind !== 'TENANT' && (
        <TextInput
          label="Audience issuer"
          value={issuer}
          onChange={(e) => setIssuer(e.currentTarget.value)}
        />
      )}
      <TextInput
        label={
          kind === 'PRINCIPAL'
            ? 'Audience subject'
            : kind === 'TENANT'
              ? 'Audience tenant'
              : kind === 'FIRM'
                ? 'Audience firm ID'
                : 'Audience resource scope ID'
        }
        value={value}
        onChange={(e) => setValue(e.currentTarget.value)}
      />
      <Button
        disabled={
          !value || (kind !== 'TENANT' && !issuer) || rules.length >= 100
        }
        onClick={add}
      >
        Add audience rule
      </Button>
      <ol>
        {rules.map((rule, i) => (
          <li key={i}>
            {rule.effect} {selectorLabel(rule)}
            <Button
              variant="subtle"
              aria-label={`Remove audience rule ${i + 1}`}
              onClick={() => setRules(rules.filter((_, index) => index !== i))}
            >
              Remove
            </Button>
          </li>
        ))}
      </ol>
    </Stack>
  );
}
