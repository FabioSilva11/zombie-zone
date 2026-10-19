package com.fabiosilva.ecosdeeldra.data

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** ORDEM FIXA: o ordinal vai no código da expedição (0 campanha, 1 sobrevivência). */
enum class ExpeditionKind { CAMPAIGN, SURVIVAL }

/** O número vai no código da expedição e no protocolo do motor: nunca reutilize nem renumere. */
enum class ContractId(val code: Int) {
    NONE(0), SILENT_RECOVERY(1), UNSTABLE_NEST(2), RED_NIGHT(3), LOST_CRATE(4), ENDURANCE(5);

    companion object {
        fun fromCode(code: Int): ContractId? = entries.firstOrNull { it.code == code }
    }
}

/** O que será jogado: tipo, fase (ou bioma, na sobrevivência), semente da ilha e contrato. */
data class ExpeditionPlan(
    val kind: ExpeditionKind,
    val stage: Int,                  // campanha: número da fase; sobrevivência: 1, 6, 11 ou 16 (bioma)
    val seed: Int,                   // 1..ExpeditionCode.SEED_MAX; cabe exato em float
    val contract: ContractId,
    val sharedSeed: Boolean = false, // veio de um código: "tentar de novo" mantém a ilha
) {
    val code: String? get() = ExpeditionCode.encode(this)

    /** Nova tentativa: ilha nova (e, na sobrevivência, outro bioma), salvo quando a ilha veio de um código compartilhado. */
    fun retry(random: Random = Random.Default): ExpeditionPlan = when {
        sharedSeed -> this
        kind == ExpeditionKind.SURVIVAL -> survival(contract, random)
        else -> copy(seed = Contracts.randomSeed(random))
    }

    companion object {
        fun standard(stage: Int, random: Random = Random.Default) =
            ExpeditionPlan(ExpeditionKind.CAMPAIGN, stage.coerceAtLeast(1), Contracts.randomSeed(random), ContractId.NONE)

        fun survival(contract: ContractId = ContractId.ENDURANCE, random: Random = Random.Default) =
            ExpeditionPlan(ExpeditionKind.SURVIVAL, random.nextInt(0, 4) * Campaign.STAGES_PER_ZONE + 1, Contracts.randomSeed(random), contract)
    }
}

/** Valores neutros = expedição padrão. 0 em ammoClips = reserva ilimitada (convenção do protocolo). */
data class EngineRules(
    val zombieCountMul: Float = 1f,
    val zombieSpeedMul: Float = 1f,
    val zombieDamageMul: Float = 1f,
    val zombieHealthMul: Float = 1f,
    val guardMul: Float = 1f,        // guardas dos pontos de interesse (nativo)
    val ammoClips: Float = 0f,       // reserva em pentes; 0 = ilimitada
    val chestAmmoPct: Int = 0,       // reserva ganha por baú, em centésimos de pente
    val visionMul: Float = 1f,       // x alcance da névoa
    val aggression: Float = 1f,      // x percepção e persistência dos zumbis
    val redSky: Boolean = false,
    val nestBomb: Float = 0f,        // 4C: chance de o ninho gerar Inchado
    val cargo: Boolean = false,      // 4D: existe carga opcional
) {
    /** Só o que é multiplicável no lado Kotlin; o resto vai pelo protocolo. */
    fun applyTo(d: Difficulty): Difficulty = d.copy(
        zombieCount = (d.zombieCount * zombieCountMul).roundToInt().coerceAtLeast(3),
        zombieHealth = d.zombieHealth * zombieHealthMul,
        zombieDamage = d.zombieDamage * zombieDamageMul,
        zombieSpeed = d.zombieSpeed * zombieSpeedMul,
    )
}

enum class ObjectiveKind { NONE, SILENCE, CARGO, ALL_NESTS_MODULE }

data class RewardTuning(
    val extractionBase: Int = 0, val extractionPerStage: Int = 0, val extractionCap: Int = 0, // ◆ ao extrair (campanha)
    val runPct: Int = 0,                      // +% sobre abates + baús + exploração (campanha) ou sobre a recompensa de ondas
    val objectiveMax: Int = 0,                // teto do bônus do objetivo opcional
    val objectivePenalty: Int = 0,            // SILENCE: perda por zumbi alertado
    val moduleId: String? = null,             // ALL_NESTS_MODULE
)

data class ContractDef(
    val id: ContractId,
    val name: String,
    val tagline: String,
    val color: Int,                 // ARGB (sem android.graphics.Color: o arquivo é testável na JVM)
    val kinds: Set<ExpeditionKind>,
    val unlockStage: Int,           // libera quando highestUnlockedStage() >= unlockStage
    val engineReady: Boolean,       // false = escondido na UI e recusado ao colar código
    val danger: Int,                // 0..3 (bolinhas na lista)
    val objective: ObjectiveKind,
    val reward: RewardTuning,
)

data class ContractOption(val def: ContractDef, val locked: Boolean, val lockReason: String?)
data class PlanCheck(val ok: Boolean, val message: String?)               // message em português para o Popup
data class ContractRun(val alerts: Int = 0, val cargoCollected: Boolean = false, val nestsDestroyed: Int = 0, val nestsTotal: Int = 0)
data class ContractReward(val extraction: Int = 0, val runBonus: Int = 0, val objective: Int = 0, val moduleId: String? = null) {
    val total: Int get() = extraction + runBonus + objective
    companion object { val NONE = ContractReward() }
}

data class ContractCard(
    val title: String, val tagline: String,
    val risks: List<String>,      // seção "CUSTO / RISCO"
    val rewards: List<String>,    // seção "RECOMPENSA"
    val objective: String?,       // seção "OBJETIVO OPCIONAL"
    val danger: Int,
    val summary: String,          // uma linha para a lista: "◆ +45 ao extrair • +50% no saque • visão curta"
)

/** Catálogo, regras do motor, recompensa e textos dos contratos de expedição. Kotlin puro (testável na JVM). */
object Contracts {
    const val SEED_RANDOM_MAX = 16_000_000                       // faixa que o motor já usa (exclusiva)
    fun randomSeed(random: Random = Random.Default): Int = random.nextInt(1, SEED_RANDOM_MAX)

    /** Módulo raro do Ninho instável (persistido em ExpeditionLog.modules). */
    const val MODULE_UNSTABLE_CORE = "nucleo_instavel"

    fun moduleName(id: String): String = when (id) {
        MODULE_UNSTABLE_CORE -> "Núcleo instável"
        else -> id
    }

    /** Caixa perdida: iguais a kCargo* de cpp/game/contract_rules.h (ContractProtocolParityTest confere). */
    const val CARGO_MIN_START_M = 450
    const val CARGO_MAX_START_M = 1400
    const val CARGO_NEST_CLEAR_M = 250

    /** Teto de fase usado nas fórmulas de extração (igual ao teto de Campaign.stage()). */
    private const val STAGE_CAP = 60

    private val CAMPAIGN = setOf(ExpeditionKind.CAMPAIGN)
    private val SURVIVAL = setOf(ExpeditionKind.SURVIVAL)
    private val BOTH = setOf(ExpeditionKind.CAMPAIGN, ExpeditionKind.SURVIVAL)

    // Cores iguais aos tokens Palette.contract* (docs/brand/IDENTIDADE.md).
    private const val COLOR_SILENT = 0xFF3CE6FF.toInt()
    private const val COLOR_NEST = 0xFFFF8A3D.toInt()
    private const val COLOR_RED_NIGHT = 0xFFFF3D3D.toInt()
    private const val COLOR_CRATE = 0xFFFFC83D.toInt()
    private const val COLOR_ENDURANCE = 0xFFA8FF3E.toInt()
    private const val COLOR_NONE = 0xFFF4F7FF.toInt()

    val all: List<ContractDef> = listOf(
        ContractDef(
            ContractId.NONE, "Expedição padrão", "Destrua os ninhos, pegue os cristais e extraia pelo portal.",
            COLOR_NONE, CAMPAIGN, 1, true, 1, ObjectiveKind.NONE, RewardTuning(),
        ),
        ContractDef(
            ContractId.SILENT_RECOVERY, "Recuperação silenciosa", "Entre, pegue o que precisa e saia sem acordar a ilha.",
            COLOR_SILENT, CAMPAIGN, 2, true, 2, ObjectiveKind.SILENCE,
            RewardTuning(extractionBase = 20, extractionPerStage = 1, extractionCap = 80, runPct = 25, objectiveMax = 60, objectivePenalty = 3),
        ),
        ContractDef(
            ContractId.UNSTABLE_NEST, "Ninho instável", "Os ninhos vazam gás: parte dos filhotes explode.",
            COLOR_NEST, CAMPAIGN, 4, true, 2, ObjectiveKind.ALL_NESTS_MODULE,
            RewardTuning(extractionBase = 10, extractionPerStage = 1, extractionCap = 60, runPct = 30, moduleId = MODULE_UNSTABLE_CORE),
        ),
        ContractDef(
            ContractId.RED_NIGHT, "Noite vermelha", "A lua sangra. Você enxerga pouco; eles, não.",
            COLOR_RED_NIGHT, BOTH, 3, true, 3, ObjectiveKind.NONE,
            RewardTuning(extractionBase = 25, extractionPerStage = 2, extractionCap = 125, runPct = 50),
        ),
        ContractDef(
            ContractId.LOST_CRATE, "Caixa perdida", "Uma carga caiu em algum ponto da ilha. Ache-a antes de extrair.",
            COLOR_CRATE, CAMPAIGN, 3, true, 2, ObjectiveKind.CARGO,
            RewardTuning(extractionBase = 10, extractionPerStage = 1, extractionCap = 60, objectiveMax = 60),
        ),
        ContractDef(
            ContractId.ENDURANCE, "Resistência", "Sobreviva às ondas. Cada onda vencida paga.",
            COLOR_ENDURANCE, SURVIVAL, 1, true, 2, ObjectiveKind.NONE, RewardTuning(),
        ),
    )

    private val byId = all.associateBy { it.id }
    fun get(id: ContractId): ContractDef = byId.getValue(id)

    fun engineRules(id: ContractId, stage: Int): EngineRules = when (id) {
        ContractId.SILENT_RECOVERY -> EngineRules(
            zombieCountMul = 0.55f,
            guardMul = 0.5f,
            ammoClips = min(7f, (30 + min(stage.coerceAtLeast(0), 40)) / 10f),
            chestAmmoPct = 50,
        )
        ContractId.UNSTABLE_NEST -> EngineRules(nestBomb = 0.35f)
        ContractId.RED_NIGHT -> EngineRules(
            zombieSpeedMul = 1.10f, zombieDamageMul = 1.15f, visionMul = 0.45f, aggression = 1.35f, redSky = true,
        )
        ContractId.LOST_CRATE -> EngineRules(cargo = true)
        ContractId.NONE, ContractId.ENDURANCE -> EngineRules()
    }

    // ------------------------------------------------------------------ apresentação e desbloqueio

    /** Antes da primeira vitória (ou da fase 2) o jogo parte direto: a primeira expedição é o tutorial jogável. */
    fun prepUnlocked(p: Progress): Boolean = p.stats.wins > 0 || p.highestUnlockedStage() >= 2

    fun options(kind: ExpeditionKind, p: Progress): List<ContractOption> {
        val reached = p.highestUnlockedStage()
        return all.filter { it.engineReady && kind in it.kinds }.map {
            val locked = reached < it.unlockStage
            ContractOption(it, locked, if (locked) "Libera na fase ${it.unlockStage}" else null)
        }
    }

    fun defaultContract(kind: ExpeditionKind, p: Progress): ContractId {
        val last = if (kind == ExpeditionKind.CAMPAIGN) p.expeditions.lastCampaign else p.expeditions.lastSurvival
        val ok = options(kind, p).any { it.def.id == last && !it.locked }
        if (ok) return last
        return if (kind == ExpeditionKind.CAMPAIGN) ContractId.NONE else ContractId.ENDURANCE
    }

    private fun num1(v: Float): String = "%.1f".format(java.util.Locale.US, v).replace('.', ',')
    private fun pct(v: Float): Int = (v * 100f).roundToInt()

    private fun extractionFor(t: RewardTuning, stage: Int): Int =
        min(t.extractionCap, t.extractionBase + t.extractionPerStage * min(stage.coerceAtLeast(1), STAGE_CAP))

    /** Textos de custo e recompensa, GERADOS dos mesmos números que o motor recebe. */
    fun card(id: ContractId, kind: ExpeditionKind, stage: Int): ContractCard {
        val def = get(id)
        val r = engineRules(id, stage)
        val t = def.reward
        val campaign = kind == ExpeditionKind.CAMPAIGN
        val risks = mutableListOf<String>()
        val rewards = mutableListOf<String>()
        var objective: String? = null
        val summary: String
        val extraction = extractionFor(t, stage)
        when (id) {
            ContractId.NONE -> {
                rewards.add("Recompensas normais da fase")
                summary = "Recompensas normais da fase"
            }
            ContractId.SILENT_RECOVERY -> {
                risks.add("Poucos inimigos: ${pct(1f - r.zombieCountMul)}% menos zumbis e ${pct(1f - r.guardMul)}% menos guardas nos ninhos")
                risks.add("Munição escassa: ${num1(r.ammoClips)} pentes de reserva (cada baú dá +${num1(r.chestAmmoPct / 100f)} pente)")
                risks.add("Suprimentos por anúncio só completam o pente atual — não repõem a reserva")
                rewards.add("Ao extrair: ◆ +$extraction")
                rewards.add("+${t.runPct}% sobre abates, baús e exploração")
                objective = "Silêncio: até ◆ +${t.objectiveMax}, −◆ ${t.objectivePenalty} por zumbi alertado (os gerados pelos ninhos não contam)"
                summary = "◆ +$extraction ao extrair • +${t.runPct}% no saque • munição escassa"
            }
            ContractId.RED_NIGHT -> {
                risks.add("Visão curta: névoa a ${pct(r.visionMul)}% do alcance normal")
                risks.add("Zumbis mais atentos: percebem você de mais longe e perseguem por mais tempo (+${pct(r.aggression - 1f)}%)")
                risks.add("Zumbis ${pct(r.zombieSpeedMul - 1f)}% mais rápidos e ${pct(r.zombieDamageMul - 1f)}% mais fortes")
                if (campaign) {
                    rewards.add("Ao extrair: ◆ +$extraction")
                    rewards.add("+${t.runPct}% sobre abates, baús e exploração")
                    summary = "◆ +$extraction ao extrair • +${t.runPct}% no saque • visão curta"
                } else {
                    rewards.add("+${t.runPct}% na recompensa de ondas e abates")
                    summary = "+${t.runPct}% na recompensa de ondas • visão curta"
                }
            }
            ContractId.ENDURANCE -> {
                risks.add("Sem ninhos nem portal: a expedição só termina quando você cai")
                risks.add("A cada 5 ondas vem uma GRANDE HORDA")
                rewards.add("◆ $SURVIVAL_WAVE_REWARD por onda vencida e ◆ 1 a cada $SURVIVAL_KILLS_PER_DIAMOND abates")
                rewards.add("+◆ $SURVIVAL_RECORD_BONUS ao bater o recorde (${SURVIVAL_RECORD_MIN_SECONDS} s ou mais)")
                summary = "◆ $SURVIVAL_WAVE_REWARD por onda vencida"
            }
            ContractId.UNSTABLE_NEST -> {
                risks.add("${pct(r.nestBomb)}% dos zumbis dos ninhos são Inchados (explodem ao chegar perto)")
                rewards.add("Ao extrair: ◆ +$extraction")
                rewards.add("+${t.runPct}% sobre abates, baús e exploração")
                rewards.add("Destruir todos os ninhos: módulo raro ${moduleName(MODULE_UNSTABLE_CORE)}")
                summary = "◆ +$extraction ao extrair • +${t.runPct}% no saque • módulo raro"
            }
            ContractId.LOST_CRATE -> {
                risks.add("A carga fica entre $CARGO_MIN_START_M e $CARGO_MAX_START_M m da largada e a mais de $CARGO_NEST_CLEAR_M m dos ninhos e do portal: exige desvio")
                risks.add("Ela só aparece no mapa e na bússola depois que você destruir o primeiro ninho")
                rewards.add("Ao extrair: ◆ +$extraction")
                objective = "Carga: ◆ +${t.objectiveMax} se for encontrada antes de entrar no portal"
                summary = "◆ +$extraction ao extrair • carga opcional"
            }
        }
        return ContractCard(def.name, def.tagline, risks, rewards, objective, def.danger, summary)
    }

    /** Valores de Rules.applySurvivalResult que o texto da Resistência repete (um teste confere com a regra real). */
    const val SURVIVAL_WAVE_REWARD = 8
    const val SURVIVAL_KILLS_PER_DIAMOND = 2
    const val SURVIVAL_RECORD_BONUS = 30
    const val SURVIVAL_RECORD_MIN_SECONDS = 60

    /** Nome curto (chip do HUD, que tem pouco espaço). */
    fun chipName(id: ContractId): String = when (id) {
        ContractId.NONE -> ""
        ContractId.SILENT_RECOVERY -> "SILÊNCIO"
        ContractId.UNSTABLE_NEST -> "NINHO INSTÁVEL"
        ContractId.RED_NIGHT -> "NOITE VERMELHA"
        ContractId.LOST_CRATE -> "CAIXA PERDIDA"
        ContractId.ENDURANCE -> "RESISTÊNCIA"
    }

    /** Uma linha para o HUD ao começar (null = usa as mensagens normais). */
    fun briefing(plan: ExpeditionPlan): String? = when (plan.contract) {
        // Sobrevivência não tem portal nem extração: a recompensa vem das ondas.
        ContractId.RED_NIGHT -> if (plan.kind == ExpeditionKind.SURVIVAL) "NOITE VERMELHA: visão curta e zumbis atentos • sobreviva às ondas" else "NOITE VERMELHA: visão curta e zumbis atentos • extraia para receber ◆"
        ContractId.SILENT_RECOVERY -> "SILÊNCIO: poupe munição, evite alertar • extraia para receber ◆"
        ContractId.UNSTABLE_NEST -> "NINHO INSTÁVEL: núcleos laranja geram Inchados • destrua os ninhos de longe"
        ContractId.LOST_CRATE -> "CAIXA PERDIDA: destrua um ninho para revelar a carga • extraia para receber ◆"
        else -> null
    }

    fun loadoutAdvice(id: ContractId, inventory: Inventory): String? = when (id) {
        ContractId.SILENT_RECOVERY -> {
            val throwables = inventory.count(Items.GRENADE) + inventory.count(Items.MOLOTOV) + inventory.count(Items.SMOKE)
            if (throwables < 2) "Leve granadas e fumaça: a reserva de munição é pequena." else null
        }
        ContractId.UNSTABLE_NEST -> {
            val throwables = inventory.count(Items.GRENADE) + inventory.count(Items.MOLOTOV)
            if (throwables < 2) "Leve granadas: os Inchados explodem perto de você." else null
        }
        ContractId.RED_NIGHT ->
            if (inventory.count(Items.MEDKIT) + inventory.count(Items.BANDAGE) == 0) "Sem curas na mochila — você só enxerga de perto." else null
        ContractId.ENDURANCE ->
            if (inventory.weaponCount() < 2) "Mais de uma arma ajuda nas ondas." else null
        else -> null
    }

    /** Texto para compartilhar a ilha (etapa 5 reutiliza). Null quando a fase não cabe em código. */
    fun shareText(plan: ExpeditionPlan): String? {
        val code = plan.code ?: return null
        val def = get(plan.contract)
        val what = if (plan.kind == ExpeditionKind.SURVIVAL) "Sobrevivência" else "Fase ${plan.stage}"
        val rule = if (plan.contract == ContractId.NONE) "" else " • ${def.name}"
        return "Zombie Zone • $what$rule\nCódigo da ilha: $code\nDigite o código em PREPARAR EXPEDIÇÃO para jogar a mesma ilha."
    }

    // ------------------------------------------------------------------ validação

    private fun validSurvivalStage(stage: Int) = stage in 1..16 && (stage - 1) % Campaign.STAGES_PER_ZONE == 0

    /** Usado ao colar um código e ao retomar um plano. */
    fun check(plan: ExpeditionPlan, p: Progress): PlanCheck {
        val def = get(plan.contract)
        if (plan.kind !in def.kinds) return PlanCheck(false, "Este código mistura tipo e contrato inválidos.")
        if (plan.kind == ExpeditionKind.SURVIVAL && !validSurvivalStage(plan.stage)) return PlanCheck(false, ExpeditionCode.message(ExpeditionCode.Problem.STAGE))
        if (!def.engineReady) return PlanCheck(false, "O contrato ${def.name} ainda não está disponível nesta versão do jogo.")
        val reached = p.highestUnlockedStage()
        // Anti-exploit: sem isso um código de fase alta pagaria primeira vitória e estrelas a um jogador novo.
        if (plan.kind == ExpeditionKind.CAMPAIGN && !StageTree.isAvailable(p, plan.stage) && !p.isStageCompleted(plan.stage))
            return PlanCheck(false, "Este código é da fase ${Campaign.stage(plan.stage).depth}. Conclua a fase anterior do caminho para jogá-lo.")
        if (reached < def.unlockStage) return PlanCheck(false, "Contrato liberado na fase ${def.unlockStage}.")
        return PlanCheck(true, null)
    }

    // ------------------------------------------------------------------ recompensa

    /**
     * Única fonte do cálculo. [runBase]: campanha = abates + baús + exploração (sem primeira vitória, estrelas e cristais);
     * sobrevivência = recompensa de ondas. Só paga em vitória (extração).
     */
    fun reward(plan: ExpeditionPlan, victory: Boolean, runBase: Int, run: ContractRun): ContractReward {
        val def = get(plan.contract)
        if (!victory || plan.contract == ContractId.NONE) return ContractReward.NONE
        val t = def.reward
        val extraction = if (plan.kind == ExpeditionKind.CAMPAIGN) extractionFor(t, plan.stage) else 0
        val runBonus = runBase.coerceAtLeast(0) * t.runPct / 100
        val objective = when (def.objective) {
            ObjectiveKind.SILENCE -> (t.objectiveMax - t.objectivePenalty * run.alerts).coerceIn(0, t.objectiveMax)
            ObjectiveKind.CARGO -> if (run.cargoCollected) t.objectiveMax else 0
            else -> 0
        }
        val module = if (def.objective == ObjectiveKind.ALL_NESTS_MODULE && run.nestsTotal > 0 && run.nestsDestroyed >= run.nestsTotal) t.moduleId else null
        return ContractReward(extraction, runBonus, objective, module)
    }
}
