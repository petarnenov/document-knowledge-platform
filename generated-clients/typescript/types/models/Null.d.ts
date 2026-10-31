export type Null = null;
export declare function instanceOfNull(value: unknown): value is Null;
export declare function NullFromJSON(value: unknown): Null;
export declare function NullFromJSONTyped(value: unknown, _ignoreDiscriminator: boolean): Null;
export declare function NullToJSON(value: Null): null;
export declare function NullToJSONTyped(value: Null, _ignoreDiscriminator?: boolean): null;
