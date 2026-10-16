// Ponte JNI: NativeBridge.kt <-> motor C++.
#include <jni.h>

#include <algorithm>
#include <memory>
#include <vector>

#include "core/log.h"
#include "core/platform.h"
#include "game/game.h"
#include "game/protocol.h"

using zz::Game;
using zz::MissionConfig;

namespace {
std::unique_ptr<Game> gGame;

Game& game() {
    if (!gGame) gGame = std::make_unique<Game>();
    return *gGame;
}
}  // namespace

#define ZZ_FN(name) Java_com_fabiosilva_ecosdeeldra_engine_NativeBridge_##name

extern "C" {

JNIEXPORT void JNICALL ZZ_FN(nativeInit)(JNIEnv* env, jobject thiz, jobject assetManager) {
    jclass cls = env->GetObjectClass(thiz);
    zz::platform::init(env, assetManager, cls);
    env->DeleteLocalRef(cls);
    game();
    LOGI("Motor nativo inicializado");
}

JNIEXPORT void JNICALL ZZ_FN(surfaceCreated)(JNIEnv*, jobject) { game().onSurfaceCreated(); }

JNIEXPORT void JNICALL ZZ_FN(surfaceChanged)(JNIEnv*, jobject, jint width, jint height) { game().onSurfaceChanged(width, height); }

JNIEXPORT void JNICALL ZZ_FN(drawFrame)(JNIEnv*, jobject, jlong nanos) { game().frame(static_cast<double>(nanos) * 1e-9); }

JNIEXPORT void JNICALL ZZ_FN(enterLobby)(JNIEnv*, jobject, jint look, jint screen) { game().enterLobby(static_cast<uint32_t>(look), screen); }

JNIEXPORT void JNICALL ZZ_FN(setPreviewSkin)(JNIEnv*, jobject, jint look) { game().setPreviewSkin(static_cast<uint32_t>(look)); }

JNIEXPORT void JNICALL ZZ_FN(rotatePreview)(JNIEnv*, jobject, jfloat dx) { game().rotatePreview(dx); }

JNIEXPORT void JNICALL ZZ_FN(startMission)(JNIEnv* env, jobject, jfloatArray values, jint look) {
    using namespace zz::proto;
    float c[C_COUNT] = {0};
    jsize n = std::min<jsize>(env->GetArrayLength(values), C_COUNT);
    env->GetFloatArrayRegion(values, 0, n, c);
    MissionConfig cfg;
    cfg.stage = static_cast<int>(c[C_STAGE]);
    cfg.skin = static_cast<uint32_t>(look);
    cfg.zombieCount = static_cast<int>(c[C_ZOMBIES]);
    cfg.zombieHealth = c[C_ZOMBIE_HP];
    cfg.zombieDamage = c[C_ZOMBIE_DMG];
    cfg.zombieSpeed = c[C_ZOMBIE_SPEED];
    cfg.playerHealth = c[C_PLAYER_HP];
    cfg.magazine = static_cast<int>(c[C_MAGAZINE]);
    cfg.power = c[C_POWER];
    cfg.crystalMask = static_cast<int>(c[C_CRYSTAL_MASK]);
    cfg.crystalTotal = static_cast<int>(c[C_CRYSTAL_TOTAL]);
    cfg.sensitivity = c[C_SENSITIVITY];
    cfg.aimAssist = c[C_AIM_ASSIST] > 0.5f;
    cfg.autoFire = c[C_AUTO_FIRE] > 0.5f;
    cfg.invertY = c[C_INVERT_Y] > 0.5f;
    cfg.seed = static_cast<uint32_t>(c[C_SEED]);
    cfg.reloadSpeed = c[C_RELOAD_SPEED] > 0 ? c[C_RELOAD_SPEED] : 1.0f;
    cfg.boostHealth = c[C_BOOST_HEALTH] > 0 ? c[C_BOOST_HEALTH] : 1.0f;
    cfg.boostDamage = c[C_BOOST_DAMAGE] > 0 ? c[C_BOOST_DAMAGE] : 1.0f;
    cfg.mode = static_cast<int>(c[C_MODE]);
    cfg.armor = c[C_ARMOR];
    cfg.regen = c[C_REGEN];
    cfg.speedMul = c[C_SPEED] > 0 ? c[C_SPEED] : 1.0f;
    cfg.headshotMul = c[C_HEADSHOT] > 0 ? c[C_HEADSHOT] : 1.0f;
    cfg.luck = c[C_LUCK] > 0 ? c[C_LUCK] : 1.0f;
    cfg.explosives = static_cast<int>(c[C_EXPLOSIVES]);
    game().setGraphics(static_cast<int>(c[C_QUALITY]), c[C_CINEMATIC] > 0.5f);
    game().startMission(cfg);
}

JNIEXPORT void JNICALL ZZ_FN(startPvp)(JNIEnv* env, jobject, jfloatArray values, jstring hostIp, jintArray looks) {
    using namespace zz::proto;
    float c[P_COUNT] = {0};
    jsize n = std::min<jsize>(env->GetArrayLength(values), P_COUNT);
    env->GetFloatArrayRegion(values, 0, n, c);
    zz::PvpConfig cfg;
    cfg.localId = std::max(0, std::min(zz::kMaxNetPlayers - 1, static_cast<int>(c[P_LOCAL_ID])));
    cfg.host = c[P_IS_HOST] > 0.5f;
    cfg.seed = static_cast<uint32_t>(c[P_SEED]);
    cfg.killLimit = static_cast<int>(c[P_KILL_LIMIT]);
    cfg.timeLimit = c[P_TIME_LIMIT];
    cfg.sensitivity = c[P_SENSITIVITY];
    cfg.invertY = c[P_INVERT_Y] > 0.5f;
    for (int i = 0; i < zz::kMaxNetPlayers; ++i) cfg.skins[i] = static_cast<uint8_t>(c[P_SKINS + i]);
    if (looks) {
        jint l[zz::kMaxNetPlayers] = {0};
        jsize ln = std::min<jsize>(env->GetArrayLength(looks), zz::kMaxNetPlayers);
        env->GetIntArrayRegion(looks, 0, ln, l);
        for (int i = 0; i < ln; ++i) cfg.looks[i] = static_cast<uint32_t>(l[i]);
    }
    if (hostIp) {
        const char* ip = env->GetStringUTFChars(hostIp, nullptr);
        cfg.hostIp = ip ? ip : "";
        if (ip) env->ReleaseStringUTFChars(hostIp, ip);
    }
    game().startPvp(cfg);
}

JNIEXPORT void JNICALL ZZ_FN(setPaused)(JNIEnv*, jobject, jboolean paused) { game().setPaused(paused); }

JNIEXPORT void JNICALL ZZ_FN(revive)(JNIEnv*, jobject) { game().revive(); }

JNIEXPORT void JNICALL ZZ_FN(setSettings)(JNIEnv*, jobject, jfloat sensitivity, jboolean aimAssist, jboolean autoFire, jboolean invertY) {
    game().setSettings(sensitivity, aimAssist, autoFire, invertY);
}

JNIEXPORT void JNICALL ZZ_FN(setGraphics)(JNIEnv*, jobject, jint quality, jboolean cinematic) { game().setGraphics(quality, cinematic); }

JNIEXPORT void JNICALL ZZ_FN(requestSupply)(JNIEnv*, jobject) { game().requestSupply(); }

JNIEXPORT void JNICALL ZZ_FN(toggleCrouch)(JNIEnv*, jobject) { game().toggleCrouch(); }

JNIEXPORT void JNICALL ZZ_FN(toggleSprint)(JNIEnv*, jobject) { game().toggleSprint(); }

JNIEXPORT void JNICALL ZZ_FN(pressMelee)(JNIEnv*, jobject) { game().pressMelee(); }

JNIEXPORT void JNICALL ZZ_FN(pressGrenade)(JNIEnv*, jobject) { game().pressGrenade(); }

JNIEXPORT void JNICALL ZZ_FN(swapShoulder)(JNIEnv*, jobject) { game().swapShoulder(); }

JNIEXPORT void JNICALL ZZ_FN(tapSlot)(JNIEnv*, jobject, jint slot) { game().tapSlot(slot); }

JNIEXPORT void JNICALL ZZ_FN(useSlot)(JNIEnv*, jobject, jint slot) { game().useSlot(slot); }

JNIEXPORT void JNICALL ZZ_FN(setThrowAim)(JNIEnv*, jobject, jboolean held, jboolean cancel) { game().setThrowAim(held, cancel); }

JNIEXPORT void JNICALL ZZ_FN(setInventory)(JNIEnv* env, jobject, jintArray values) {
    int data[zz::proto::kInvIntCount] = {0};
    jsize n = std::min<jsize>(env->GetArrayLength(values), zz::proto::kInvIntCount);
    env->GetIntArrayRegion(values, 0, n, reinterpret_cast<jint*>(data));
    game().setInventory(data, n);
}

JNIEXPORT void JNICALL ZZ_FN(setMove)(JNIEnv*, jobject, jfloat x, jfloat y) { game().setMove(x, y); }

JNIEXPORT void JNICALL ZZ_FN(addLook)(JNIEnv*, jobject, jfloat dx, jfloat dy) { game().addLook(dx, dy); }

JNIEXPORT void JNICALL ZZ_FN(setFire)(JNIEnv*, jobject, jboolean held) { game().setFire(held); }

JNIEXPORT void JNICALL ZZ_FN(setAim)(JNIEnv*, jobject, jboolean on) { game().setAim(on); }
JNIEXPORT void JNICALL ZZ_FN(setAimStage)(JNIEnv*, jobject, jint stage) { game().setAimStage(stage); }

JNIEXPORT void JNICALL ZZ_FN(pressJump)(JNIEnv*, jobject) { game().pressJump(); }

JNIEXPORT void JNICALL ZZ_FN(pressReload)(JNIEnv*, jobject) { game().pressReload(); }

JNIEXPORT void JNICALL ZZ_FN(copyHud)(JNIEnv* env, jobject, jfloatArray out) {
    float buffer[zz::proto::H_SIZE];
    jsize n = std::min<jsize>(env->GetArrayLength(out), zz::proto::H_SIZE);
    game().copyHud(buffer, n);
    env->SetFloatArrayRegion(out, 0, n, buffer);
}

JNIEXPORT jint JNICALL ZZ_FN(drainEvents)(JNIEnv* env, jobject, jintArray out) {
    jsize capacity = env->GetArrayLength(out);
    std::vector<int> buffer(static_cast<size_t>(capacity));
    int n = game().drainEvents(buffer.data(), capacity);
    if (n > 0) env->SetIntArrayRegion(out, 0, n, buffer.data());
    return n;
}

JNIEXPORT void JNICALL ZZ_FN(copyResult)(JNIEnv* env, jobject, jfloatArray out) {
    float buffer[zz::proto::R_SIZE];
    jsize n = std::min<jsize>(env->GetArrayLength(out), zz::proto::R_SIZE);
    game().copyResult(buffer, n);
    env->SetFloatArrayRegion(out, 0, n, buffer);
}

JNIEXPORT jint JNICALL ZZ_FN(minimapSize)(JNIEnv*, jobject) { return game().minimapSize(); }

JNIEXPORT void JNICALL ZZ_FN(copyMinimap)(JNIEnv* env, jobject, jintArray out) {
    jsize n = env->GetArrayLength(out);
    std::vector<uint32_t> buffer(static_cast<size_t>(n));
    game().copyMinimap(buffer.data(), n);
    env->SetIntArrayRegion(out, 0, n, reinterpret_cast<const jint*>(buffer.data()));
}

JNIEXPORT jint JNICALL ZZ_FN(bigMapSize)(JNIEnv*, jobject) { return game().bigMapSize(); }

JNIEXPORT void JNICALL ZZ_FN(copyBigMap)(JNIEnv* env, jobject, jintArray out) {
    jsize n = env->GetArrayLength(out);
    std::vector<uint32_t> buffer(static_cast<size_t>(n));
    game().copyBigMap(buffer.data(), n);
    env->SetIntArrayRegion(out, 0, n, reinterpret_cast<const jint*>(buffer.data()));
}

}  // extern "C"
