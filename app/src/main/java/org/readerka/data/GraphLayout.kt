package org.readerka.data

import org.readerka.data.LibraryGraph
import kotlin.math.*

data class GraphPoint(var x: Double, var y: Double)

/** Bounded, deterministic layout with spatial buckets. Runs off the Swing event thread. */
object GraphLayout {
    fun arrange(graph: LibraryGraph, cancelled: () -> Boolean = { false }): Map<String, GraphPoint> {
        val n = graph.nodes.size
        if (n == 0) return emptyMap()
        val index = graph.nodes.mapIndexed { i, node -> node.id to i }.toMap()
        val points = Array(n) { i ->
            val r = 100.0 * sqrt(i.toDouble())
            GraphPoint(cos(i * 2.399963) * r, sin(i * 2.399963) * r)
        }
        val links = graph.edges.map { index.getValue(it.from) to index.getValue(it.to) }.distinct()
        val iterations = if (n < 600) 200 else 90
        repeat(iterations) { step ->
            if (cancelled()) return emptyMap()
            val buckets = mutableMapOf<Pair<Int, Int>, MutableList<Int>>()
            points.forEachIndexed { i, p -> buckets.getOrPut(floor(p.x / 260).toInt() to floor(p.y / 260).toInt()) { mutableListOf() }.add(i) }
            val dx = DoubleArray(n); val dy = DoubleArray(n)
            points.forEachIndexed { i, p ->
                val bx = floor(p.x / 260).toInt(); val by = floor(p.y / 260).toInt()
                for (x in bx-1..bx+1) for (y in by-1..by+1) for (j in buckets[x to y].orEmpty()) {
                    if (j <= i) continue
                    var vx = p.x - points[j].x; var vy = p.y - points[j].y
                    if (abs(vx) + abs(vy) < .01) { vx = 1.0; vy = .5 }
                    val distance = hypot(vx, vy).coerceAtLeast(1.0)
                    val force = (22000 / (distance * distance)).coerceAtMost(40.0)
                    val fx = vx / distance * force; val fy = vy / distance * force
                    dx[i] += fx; dy[i] += fy; dx[j] -= fx; dy[j] -= fy
                }
            }
            links.forEach { (i, j) ->
                val vx = points[j].x - points[i].x; val vy = points[j].y - points[i].y
                val distance = hypot(vx, vy).coerceAtLeast(1.0)
                val force = (distance - 190) * .025
                val fx = vx / distance * force; val fy = vy / distance * force
                dx[i] += fx; dy[i] += fy; dx[j] -= fx; dy[j] -= fy
            }
            val limit = 18.0 * (1 - step.toDouble() / iterations) + 1
            points.forEachIndexed { i, p ->
                val distance = hypot(dx[i], dy[i]).coerceAtLeast(1.0)
                val factor = min(1.0, limit / distance)
                p.x += dx[i] * factor; p.y += dy[i] * factor
            }
        }
        return graph.nodes.mapIndexed { i, node -> node.id to points[i] }.toMap()
    }
}
