#pragma once

#include <GLES3/gl3.h>

#include <string>

namespace zz {

GLuint compileProgram(const char* vertexSource, const char* fragmentSource, const char* label);

struct ShaderProgram {
    GLuint id = 0;
    GLint uniform(const char* name) const { return glGetUniformLocation(id, name); }
};

}  // namespace zz
