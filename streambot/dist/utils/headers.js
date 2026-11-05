const HEADER_FLAGS = new Map([
    ['--referer', 'Referer'],
    ['--referrer', 'Referer'],
    ['--origin', 'Origin'],
    ['--user-agent', 'User-Agent'],
    ['--ua', 'User-Agent'],
]);
const GENERIC_HEADER_FLAGS = new Set(['--header']);
const HEADER_NAME_REGEX = /^[A-Za-z0-9!#$%&'*+.^_`|~-]+$/;
export function hasHeaderFlags(args) {
    return args.some(arg => HEADER_FLAGS.has(arg.toLowerCase()) || GENERIC_HEADER_FLAGS.has(arg.toLowerCase()));
}
const QUOTE_CHARS = new Set(['"', '“', '”']);
export function tokenize(text) {
    const tokens = [];
    const chars = [...text];
    let current = '';
    let inQuotes = false;
    let hasToken = false;
    for (let i = 0; i < chars.length; i++) {
        const char = chars[i];
        if (char === '\\' && QUOTE_CHARS.has(chars[i + 1])) {
            current += chars[++i];
            hasToken = true;
        }
        else if (QUOTE_CHARS.has(char)) {
            inQuotes = !inQuotes;
            hasToken = true;
        }
        else if (!inQuotes && /\s/.test(char)) {
            if (hasToken) {
                tokens.push(current);
                current = '';
                hasToken = false;
            }
        }
        else {
            current += char;
            hasToken = true;
        }
    }
    if (inQuotes) {
        throw new Error('Unterminated quote in command.');
    }
    if (hasToken) {
        tokens.push(current);
    }
    return tokens;
}
function normalizeHeaderName(name) {
    return name
        .split('-')
        .map(part => part.charAt(0).toUpperCase() + part.slice(1).toLowerCase())
        .join('-');
}
function validateHeaderValue(name, value) {
    if (/[\r\n]/.test(value)) {
        throw new Error(`Invalid value for header \`${name}\`: line breaks are not allowed.`);
    }
}
export function parseHeaderFlags(tokens) {
    const headers = Object.create(null);
    const inputTokens = [];
    for (let i = 0; i < tokens.length; i++) {
        const token = tokens[i];
        const flag = token.toLowerCase();
        const headerName = HEADER_FLAGS.get(flag);
        const isGeneric = GENERIC_HEADER_FLAGS.has(flag);
        if (!headerName && !isGeneric) {
            inputTokens.push(token);
            continue;
        }
        const value = tokens[++i];
        if (value === undefined || value === '' || value.startsWith('-')) {
            throw new Error(`Missing value for \`${token}\`.`);
        }
        if (headerName) {
            validateHeaderValue(headerName, value);
            headers[headerName] = value;
        }
        else {
            const separator = value.indexOf(':');
            const name = separator > 0 ? value.slice(0, separator).trim() : '';
            if (!name || !HEADER_NAME_REGEX.test(name)) {
                throw new Error(`Invalid header \`${value}\`. Use the format \`"Name: Value"\`.`);
            }
            const headerValue = value.slice(separator + 1).trim();
            validateHeaderValue(name, headerValue);
            headers[normalizeHeaderName(name)] = headerValue;
        }
    }
    let input = inputTokens.join(' ');
    if (input.startsWith('<') && input.endsWith('>')) {
        input = input.slice(1, -1);
    }
    return { input, headers };
}
//# sourceMappingURL=headers.js.map