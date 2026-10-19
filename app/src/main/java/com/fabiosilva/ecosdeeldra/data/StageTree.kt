package com.fabiosilva.ecosdeeldra.data

/**
 * Árvore infinita de fases, como o mapa HTML "Árvore de zonas".
 *
 * O nó 1 é a raiz. Os filhos de [n] são [2n] e [2n+1], então o id do nó identifica o caminho inteiro e
 * a profundidade (1 = raiz) é o nível de dificuldade. Um nó só fica disponível depois que o pai foi vencido;
 * o jogador escolhe qual ramo seguir. Como o pai é sempre menor que o filho, qualquer conjunto de fases
 * 1..k vencidas é válido: os saves lineares antigos continuam funcionando sem migração.
 */
object StageTree {
    const val ROOT = 1

    /** Último id usável (2^30 - 1): cerca de 30 níveis de profundidade, mais que qualquer partida alcança. */
    const val MAX_ID = (1 shl 30) - 1

    /** Profundidade do nó: a raiz é 1; o nó n fica em floor(log2 n) + 1. */
    fun depth(id: Int): Int = 32 - Integer.numberOfLeadingZeros(id.coerceAtLeast(1))

    fun parent(id: Int): Int? = if (id <= ROOT) null else id / 2

    fun children(id: Int): List<Int> =
        if (id < ROOT || id * 2 + 1 > MAX_ID) emptyList() else listOf(id * 2, id * 2 + 1)

    /** Disponível = raiz ou pai já vencido. */
    fun isAvailable(p: Progress, id: Int): Boolean = parent(id)?.let { p.isStageCompleted(it) } ?: true

    /** Próximo nó a jogar por padrão: o disponível de menor id ainda não vencido. */
    fun frontier(p: Progress): Int {
        if (!p.isStageCompleted(ROOT)) return ROOT
        var best = Int.MAX_VALUE
        for ((id, record) in p.stages) {
            if (!record.completed) continue
            for (child in children(id)) if (!p.isStageCompleted(child) && child < best) best = child
        }
        return if (best == Int.MAX_VALUE) ROOT else best
    }
}
