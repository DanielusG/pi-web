package app.pimobile.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * A node of `tree` from GET /api/sessions/[id]. The server keeps only roots,
 * branch points and leaves (lib/project-tree.ts); contracted entry ids ride
 * along in [compressedIds].
 */
data class TreeNode(
    val id: String,
    /** The message role of a message entry, else null. */
    val role: String?,
    val isMessage: Boolean,
    /** Web: getLabel on the entry itself. */
    val label: String,
    val preview: BranchPreview?,
    val compressedIds: List<String>,
    val children: List<TreeNode>,
)

data class BranchPreview(val role: String?, val text: String)

/** One row of the branch list; [parentLines] says, per ancestor depth, whether its guide line continues. */
data class BranchRow(
    val targetId: String,
    val label: String,
    val role: String?,
    val skipped: Int,
    val isLast: Boolean,
    val parentLines: List<Boolean>,
    val active: Boolean,
    val onPath: Boolean,
)

/** Web: components/BranchNavigator.tsx, with its recursive walks made iterative. */
object SessionTree {
    fun parse(json: JsonArray?): List<TreeNode> = json.orEmpty().mapNotNull { (it as? JsonObject)?.let(::node) }

    // The server caps the projected depth (MAX_PROJECTED_TREE_DEPTH), so recursion stays shallow.
    private fun node(json: JsonObject): TreeNode? {
        val entry = json.obj("entry") ?: return null
        val id = entry.str("id") ?: return null
        val message = entry.obj("message")?.takeIf { entry.type == "message" }
        return TreeNode(
            id = id,
            role = message?.str("role"),
            isMessage = message != null,
            label = label(entry, message),
            preview = json.obj("branchPreview")?.let { BranchPreview(it.str("role"), it.str("text").orEmpty()) },
            compressedIds = json.arr("compressedEntryIds").strings(),
            children = parse(json.arr("children")),
        )
    }

    private fun label(entry: JsonObject, message: JsonObject?): String {
        if (message != null) {
            var text = when (val content = message["content"]) {
                is JsonArray -> content
                    .mapNotNull { (it as? JsonObject)?.takeIf { block -> block.type == "text" }?.str("text") }
                    .joinToString(" ")
                else -> Messages.contentText(content)
            }
            if (text.length > 40) text = text.take(40) + "…"
            if (text.isNotEmpty()) return text
            if (message.str("role") == "assistant") return "[assistant]"
        }
        return entry.type.orEmpty()
    }

    fun hasBranches(nodes: List<TreeNode>): Boolean {
        // Sessions branched from the very first message have multiple roots.
        if (nodes.size > 1) return true
        val stack = ArrayDeque(nodes)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node.children.size > 1) return true
            stack.addAll(node.children)
        }
        return false
    }

    /** The branch rows in display order: the top-level branches and everything under them. */
    fun rows(tree: List<TreeNode>, leafId: String?): List<BranchRow> {
        if (!hasBranches(tree)) return emptyList()
        val path = activePath(tree, leafId)
        val rows = mutableListOf<BranchRow>()
        val stack = ArrayDeque<Pending>()
        fun pushChildren(children: List<TreeNode>, lines: List<Boolean>) {
            for (i in children.indices.reversed()) stack.addLast(Pending(children[i], i == children.lastIndex, lines))
        }
        pushChildren(topLevel(tree), emptyList())
        while (stack.isNotEmpty()) {
            val (node, isLast, lines) = stack.removeLast()
            val chain = compressChain(node)
            val preview = chain.preview
            rows += BranchRow(
                targetId = chain.node.id,
                label = preview?.text ?: chain.labelNode.label,
                role = if (preview != null) preview.role else chain.labelNode.takeIf { it.isMessage }?.role,
                skipped = chain.skipped,
                isLast = isLast,
                parentLines = lines,
                active = chain.node.id in path,
                onPath = node.id in path || chain.node.id in path,
            )
            pushChildren(chain.node.children, lines + !isLast)
        }
        return rows
    }

    private data class Pending(val node: TreeNode, val isLast: Boolean, val lines: List<Boolean>)

    private class Chain(val node: TreeNode, val skipped: Int, val preview: BranchPreview?, val labelNode: TreeNode)

    /** Web: compressChain — a single-child chain collapses into its first branching or leaf node. */
    private fun compressChain(start: TreeNode): Chain {
        var current = start
        var preview = current.preview
        var labelNode = current.takeIf { it.isMessage }
        var skipped = current.compressedIds.size
        while (current.children.size == 1) {
            current = current.children[0]
            preview = preview ?: current.preview
            if (labelNode == null && current.isMessage) labelNode = current
            skipped += 1 + current.compressedIds.size
        }
        return Chain(current, skipped, preview, labelNode ?: current)
    }

    /** Web: selectTopLevelBranches — the roots when there are several, else the first branch point's children. */
    private fun topLevel(tree: List<TreeNode>): List<TreeNode> = when {
        tree.size > 1 -> tree
        tree.isEmpty() -> emptyList()
        else -> compressChain(tree[0]).node.children.takeIf { it.size > 1 }.orEmpty()
    }

    /** Web: buildActivePath — the node ids from a root down to the one holding [leafId]. */
    private fun activePath(nodes: List<TreeNode>, leafId: String?): Set<String> {
        if (leafId == null) return emptySet()
        val stack = ArrayDeque(nodes.map { it to listOf(it.id) })
        while (stack.isNotEmpty()) {
            val (node, path) = stack.removeLast()
            if (node.id == leafId || leafId in node.compressedIds) return path.toSet()
            node.children.forEach { stack.addLast(it to path + it.id) }
        }
        return emptySet()
    }
}
