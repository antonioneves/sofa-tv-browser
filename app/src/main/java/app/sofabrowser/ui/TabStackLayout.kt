package app.sofabrowser.ui

/** Where the tabs' icons sit in the bar's tab stack ([TabStack]), in pixels from its top. Plain Kotlin, so it is unit-tested. */
object TabStackLayout {

    /** How tall [n] icons of [square] pixels are, [slot] pixels apart: the room they take when none overlaps. */
    fun natural(n: Int, slot: Int, square: Int) = if (n <= 0) 0 else (n - 1) * slot + square

    /**
     * The top of each of [n] icons in a stack [height] pixels tall. When they all fit they are [slot] apart. When they
     * don't, they overlap like a fanned deck, and the one at [focus] gets room: its neighbours move off it as far as
     * the stack allows, and the others share what is left, at least [minStep] apart while there is room for that.
     * The last icon always ends at the foot of the stack.
     */
    fun tops(n: Int, focus: Int, height: Int, slot: Int, square: Int, minStep: Int): IntArray {
        if (n <= 0) return IntArray(0)
        if (n == 1 || natural(n, slot, square) <= height) return IntArray(n) { it * slot }
        val f = focus.coerceIn(0, n - 1)
        val room = (height - square).coerceAtLeast(0).toFloat()
        val gaps = n - 1
        // The gaps on either side of the focused icon, and the others.
        val near = (if (f > 0) 1 else 0) + (if (f < n - 1) 1 else 0)
        val others = gaps - near
        val step = minOf(minStep.toFloat(), room / gaps)
        var small: Float
        var big: Float
        if (others == 0) {
            big = room / near
            small = big
        } else {
            small = maxOf(step, (room - near * slot) / others)
            big = minOf(slot.toFloat(), (room - others * small) / near)
            if (big < small) { small = room / gaps; big = small }
        }
        val tops = IntArray(n)
        var y = 0f
        for (i in 1 until n) {
            // The gap above icon i: next to the focused one when it is the one just above it or the focused one itself.
            y += if (i == f || i - 1 == f) big else small
            tops[i] = Math.round(y)
        }
        return tops
    }
}
