#include "json.h"

#include <cctype>
#include <cstdlib>

namespace zz {

namespace {
const JsonValue& nullValue() {
    static const JsonValue value;
    return value;
}
}  // namespace

const JsonValue& JsonValue::operator[](size_t index) const {
    if (type_ != Type::Array || index >= array_.size()) return nullValue();
    return array_[index];
}

const JsonValue& JsonValue::operator[](const std::string& key) const {
    if (type_ != Type::Object) return nullValue();
    auto it = object_.find(key);
    return it == object_.end() ? nullValue() : it->second;
}

class JsonParser {
public:
    JsonParser(const char* text, size_t length) : p_(text), end_(text + length) {}

    bool parseValue(JsonValue& out, int depth = 0) {
        if (depth > 64) return fail("aninhamento excessivo");
        skipSpace();
        if (p_ >= end_) return fail("fim inesperado");
        char c = *p_;
        if (c == '{') return parseObject(out, depth);
        if (c == '[') return parseArray(out, depth);
        if (c == '"') {
            out.type_ = JsonValue::Type::String;
            return parseString(out.string_);
        }
        if (c == 't' && match("true")) { out.type_ = JsonValue::Type::Bool; out.bool_ = true; return true; }
        if (c == 'f' && match("false")) { out.type_ = JsonValue::Type::Bool; out.bool_ = false; return true; }
        if (c == 'n' && match("null")) { out.type_ = JsonValue::Type::Null; return true; }
        return parseNumber(out);
    }

    void skipSpace() {
        while (p_ < end_ && (*p_ == ' ' || *p_ == '\n' || *p_ == '\r' || *p_ == '\t')) ++p_;
    }
    bool atEnd() { skipSpace(); return p_ >= end_ || *p_ == '\0'; }
    const std::string& error() const { return error_; }

private:
    bool fail(const char* message) {
        if (error_.empty()) error_ = message;
        return false;
    }

    bool match(const char* word) {
        const char* q = p_;
        while (*word) {
            if (q >= end_ || *q != *word) return false;
            ++q; ++word;
        }
        p_ = q;
        return true;
    }

    bool parseNumber(JsonValue& out) {
        const char* start = p_;
        while (p_ < end_ && (std::isdigit(static_cast<unsigned char>(*p_)) || *p_ == '-' || *p_ == '+' || *p_ == '.' || *p_ == 'e' || *p_ == 'E')) ++p_;
        if (p_ == start) return fail("valor inválido");
        std::string token(start, p_);
        char* parsedEnd = nullptr;
        out.number_ = std::strtod(token.c_str(), &parsedEnd);
        out.type_ = JsonValue::Type::Number;
        return parsedEnd && *parsedEnd == '\0';
    }

    static void appendUtf8(std::string& s, unsigned code) {
        if (code < 0x80) s += static_cast<char>(code);
        else if (code < 0x800) { s += static_cast<char>(0xC0 | (code >> 6)); s += static_cast<char>(0x80 | (code & 0x3F)); }
        else { s += static_cast<char>(0xE0 | (code >> 12)); s += static_cast<char>(0x80 | ((code >> 6) & 0x3F)); s += static_cast<char>(0x80 | (code & 0x3F)); }
    }

    bool parseString(std::string& out) {
        ++p_;  // aspas
        out.clear();
        while (p_ < end_ && *p_ != '"') {
            char c = *p_++;
            if (c != '\\') { out += c; continue; }
            if (p_ >= end_) return fail("escape incompleto");
            char e = *p_++;
            switch (e) {
                case '"': out += '"'; break;
                case '\\': out += '\\'; break;
                case '/': out += '/'; break;
                case 'b': out += '\b'; break;
                case 'f': out += '\f'; break;
                case 'n': out += '\n'; break;
                case 'r': out += '\r'; break;
                case 't': out += '\t'; break;
                case 'u': {
                    if (end_ - p_ < 4) return fail("escape unicode incompleto");
                    unsigned code = static_cast<unsigned>(std::strtoul(std::string(p_, p_ + 4).c_str(), nullptr, 16));
                    p_ += 4;
                    appendUtf8(out, code);
                    break;
                }
                default: return fail("escape desconhecido");
            }
        }
        if (p_ >= end_) return fail("string sem fim");
        ++p_;
        return true;
    }

    bool parseArray(JsonValue& out, int depth) {
        ++p_;
        out.type_ = JsonValue::Type::Array;
        skipSpace();
        if (p_ < end_ && *p_ == ']') { ++p_; return true; }
        while (p_ < end_) {
            out.array_.emplace_back();
            if (!parseValue(out.array_.back(), depth + 1)) return false;
            skipSpace();
            if (p_ < end_ && *p_ == ',') { ++p_; continue; }
            if (p_ < end_ && *p_ == ']') { ++p_; return true; }
            return fail("esperado , ou ]");
        }
        return fail("array sem fim");
    }

    bool parseObject(JsonValue& out, int depth) {
        ++p_;
        out.type_ = JsonValue::Type::Object;
        skipSpace();
        if (p_ < end_ && *p_ == '}') { ++p_; return true; }
        while (p_ < end_) {
            skipSpace();
            if (p_ >= end_ || *p_ != '"') return fail("esperada chave");
            std::string key;
            if (!parseString(key)) return false;
            skipSpace();
            if (p_ >= end_ || *p_ != ':') return fail("esperado :");
            ++p_;
            JsonValue value;
            if (!parseValue(value, depth + 1)) return false;
            out.object_[key] = std::move(value);
            skipSpace();
            if (p_ < end_ && *p_ == ',') { ++p_; continue; }
            if (p_ < end_ && *p_ == '}') { ++p_; return true; }
            return fail("esperado , ou }");
        }
        return fail("objeto sem fim");
    }

    const char* p_;
    const char* end_;
    std::string error_;
};

std::unique_ptr<JsonValue> JsonValue::parse(const char* text, size_t length, std::string* error) {
    auto root = std::make_unique<JsonValue>();
    JsonParser parser(text, length);
    if (!parser.parseValue(*root)) {
        if (error) *error = parser.error();
        return nullptr;
    }
    return root;
}

}  // namespace zz
