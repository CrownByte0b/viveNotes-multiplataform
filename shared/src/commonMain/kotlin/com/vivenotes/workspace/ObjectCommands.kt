package com.vivenotes.workspace

/**
 * The prime object right-click menu's commands (`diagram.md`, Prime Object Class): the base object
 * toolkit's Copy and Delete, and Cut, which is the two in one undo step.
 */
enum class ObjectCommand { Cut, Copy, Delete }

/**
 * [command] on what is selected, as Ctrl+X, Ctrl+C and Delete act on it; or, given [objectId], on
 * that prime object as a click would hold it — with everything locked to it — whatever is selected:
 * a table's own commands in its cells' menu. Copying it leaves the selection and the cell being
 * edited as they were, as Copy text box does; cutting or deleting it takes them with it.
 *
 * Lock means stays put, not protected: locked objects are cut, copied and deleted like any other.
 * Cut and Delete change nothing on a page that cannot be edited.
 */
fun WorkspaceState.runObjectCommand(command: ObjectCommand, objectId: String? = null): WorkspaceState {
    val held = if (objectId == null) this
        else selectObject(objectId).takeIf { objectId in it.selectedObjectIds } ?: return this
    if (command == ObjectCommand.Copy) {
        val copied = held.copySelectedObjects()
        return if (copied === held) this else copy(canvasClipboard = copied.canvasClipboard)
    }
    if (selectedPage?.editable != true) return this
    val next = if (command == ObjectCommand.Cut) held.cutSelectedObjects() else held.deleteSelectedObjects()
    return if (next === held) this else next
}
