// Parser JSON compacto (somente leitura) usado para o cabeçalho dos arquivos GLB.
#pragma once

#include <map>
#include <memory>
#include <string>
#include <vector>

namespace zz {

class JsonValue {
public:
    enum class Type { Null, Bool, Number, String, Array, Object };

    Type type() const { return type_; }
    bool isNull() const { return type_ == Type::Null; }
    bool isNumber() const { return type_ == Type::Number; }
    bool isString() const { return type_ == Type::String; }
    bool isArray() const { return type_ == Type::Array; }
    bool isObject() const { return type_ == Type::Object; }

    double number(double fallback = 0) const { return type_ == Type::Number ? number_ : fallback; }
    int integer(int fallback = 0) const { return type_ == Type::Number ? static_cast<int>(number_) : fallback; }
    bool boolean(bool fallback = false) const { return type_ == Type::Bool ? bool_ : fallback; }
    const std::string& string() const { return string_; }

    size_t size() const { return type_ == Type::Array ? array_.size() : (type_ == Type::Object ? object_.size() : 0); }
    const JsonValue& operator[](size_t index) const;
    const JsonValue& operator[](const std::string& key) const;
    bool has(const std::string& key) const { return type_ == Type::Object && object_.count(key) > 0; }

    static std::unique_ptr<JsonValue> parse(const char* text, size_t length, std::string* error = nullptr);

private:
    friend class JsonParser;
    Type type_ = Type::Null;
    double number_ = 0;
    bool bool_ = false;
    std::string string_;
    std::vector<JsonValue> array_;
    std::map<std::string, JsonValue> object_;
};

}  // namespace zz
