// Minimal, dependency-free Markdown renderer for AI responses.
// All input is HTML-escaped first, so model output can never inject markup.
(function () {
    function esc(s) {
        return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
    }

    function inline(text) {
        const codes = [];
        // Pull out code spans first so their contents are not formatted.
        let t = esc(text).replace(/`([^`]+)`/g, (m, c) => {
            codes.push(c);
            return "\u0000" + (codes.length - 1) + "\u0000";
        });
        t = t.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g,
            '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');
        t = t.replace(/\*\*([^*]+)\*\*|__([^_]+)__/g, (m, a, b) => "<strong>" + (a || b) + "</strong>");
        t = t.replace(/(^|[^*\w])\*([^*\s][^*]*)\*(?!\*)/g, "$1<em>$2</em>");
        t = t.replace(/(^|[^_\w])_([^_\s][^_]*)_(?![_\w])/g, "$1<em>$2</em>");
        t = t.replace(/~~([^~]+)~~/g, "<del>$1</del>");
        return t.replace(/\u0000(\d+)\u0000/g, (m, i) => "<code>" + codes[i] + "</code>");
    }

    function splitRow(line) {
        return line.trim().replace(/^\||\|$/g, "").split("|").map(c => c.trim());
    }

    const isTableSep = l => /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/.test(l) && l.includes("-");
    const isBlockStart = l => /^(#{1,6}\s|```|>\s?|\s*[-*+]\s+|\s*\d+[.)]\s+|(-{3,}|\*{3,}|_{3,})\s*$)/.test(l);

    window.renderMarkdown = function (src) {
        const lines = String(src == null ? "" : src).replace(/\r\n?/g, "\n").split("\n");
        const out = [];
        let i = 0;
        while (i < lines.length) {
            const line = lines[i];
            if (!line.trim()) {
                i++;
                continue;
            }
            const fence = line.match(/^```\s*([\w+-]*)/);
            if (fence) {
                const code = [];
                i++;
                while (i < lines.length && !/^```\s*$/.test(lines[i])) {
                    code.push(lines[i++]);
                }
                i++;
                out.push("<pre><code>" + esc(code.join("\n")) + "</code></pre>");
                continue;
            }
            const h = line.match(/^(#{1,6})\s+(.*?)\s*#*\s*$/);
            if (h) {
                out.push(`<h${h[1].length}>${inline(h[2])}</h${h[1].length}>`);
                i++;
                continue;
            }
            if (/^(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
                out.push("<hr>");
                i++;
                continue;
            }
            if (/^>\s?/.test(line)) {
                const q = [];
                while (i < lines.length && /^>\s?/.test(lines[i])) {
                    q.push(lines[i++].replace(/^>\s?/, ""));
                }
                out.push("<blockquote>" + window.renderMarkdown(q.join("\n")) + "</blockquote>");
                continue;
            }
            if (line.includes("|") && i + 1 < lines.length && isTableSep(lines[i + 1])) {
                const head = splitRow(line);
                i += 2;
                const rows = [];
                while (i < lines.length && lines[i].trim() && lines[i].includes("|")) {
                    rows.push(splitRow(lines[i++]));
                }
                out.push("<div class=\"md-table\"><table><thead><tr>"
                    + head.map(c => "<th>" + inline(c) + "</th>").join("")
                    + "</tr></thead><tbody>"
                    + rows.map(r => "<tr>" + r.map(c => "<td>" + inline(c) + "</td>").join("") + "</tr>").join("")
                    + "</tbody></table></div>");
                continue;
            }
            const li = line.match(/^(\s*)([-*+]|\d+[.)])\s+(.*)$/);
            if (li) {
                const ordered = /\d/.test(li[2]);
                const tag = ordered ? "ol" : "ul";
                const items = [];
                while (i < lines.length) {
                    const m = lines[i].match(/^(\s*)([-*+]|\d+[.)])\s+(.*)$/);
                    if (m && /\d/.test(m[2]) === ordered && m[1].length < li[1].length + 2) {
                        items.push(m[3]);
                        i++;
                    } else if (m && m[1].length >= li[1].length + 2 && items.length) {
                        // Nested item: fold into the previous item as an indented sub-list.
                        const sub = [];
                        while (i < lines.length && /^\s{2,}\S/.test(lines[i])) {
                            sub.push(lines[i++].replace(/^\s{2}/, ""));
                        }
                        items[items.length - 1] += "\n" + sub.join("\n");
                    } else if (lines[i].trim() && /^\s+\S/.test(lines[i]) && items.length && !m) {
                        items[items.length - 1] += " " + lines[i++].trim();
                    } else {
                        break;
                    }
                }
                out.push(`<${tag}>` + items.map(it => {
                    const [first, ...rest] = it.split("\n");
                    return "<li>" + inline(first) + (rest.length ? window.renderMarkdown(rest.join("\n")) : "") + "</li>";
                }).join("") + `</${tag}>`);
                continue;
            }
            const para = [];
            while (i < lines.length && lines[i].trim() && (!para.length || !isBlockStart(lines[i]))) {
                para.push(lines[i++]);
            }
            out.push("<p>" + para.map(inline).join("<br>") + "</p>");
        }
        return out.join("\n");
    };
})();
