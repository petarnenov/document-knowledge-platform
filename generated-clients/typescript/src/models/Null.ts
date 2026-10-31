// Deterministic OAS 3.1 null-only model supplied by the SDK generation pipeline.
export type Null = null;
export function instanceOfNull(value: unknown): value is Null { return value === null; }
export function NullFromJSON(value: unknown): Null {
    if (value !== null) throw new TypeError('Expected null-only API field');
    return null;
}
export function NullFromJSONTyped(value: unknown, _ignoreDiscriminator: boolean): Null {
    return NullFromJSON(value);
}
export function NullToJSON(value: Null): null { return NullFromJSON(value); }
export function NullToJSONTyped(value: Null, _ignoreDiscriminator?: boolean): null {
    return NullFromJSON(value);
}
