export interface ParsedPlayInput {
    input: string;
    headers: Record<string, string>;
}
export declare function hasHeaderFlags(args: string[]): boolean;
export declare function tokenize(text: string): string[];
export declare function parseHeaderFlags(tokens: string[]): ParsedPlayInput;
