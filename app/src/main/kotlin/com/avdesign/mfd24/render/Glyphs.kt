// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.render

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF

/**
 * Vector pictograms for the site-lock readout.
 *
 * Seven silhouettes for the site lock, plus a heart and a walking figure for the optional sensor
 * slots beside the hub, where a pictogram says what two or three letters had to spell.
 *
 * The site set, chosen by the site's type and flags: an airliner or a fast jet for a civil
 * or military airfield, a plain or an armed rotorcraft for a helipad, a merchantman or a warship
 * for a civil or naval port, and a launch complex for a spaceport either way. Military sites are
 * additionally drawn in the palette's accent colour, which is what actually carries the
 * distinction at a glance -- the silhouette confirms it once you look.
 *
 * These are drawn as paths rather than as the `✈ ⚓ 🚀` code points on purpose — emoji coverage in
 * the Wear OS system fonts is unreliable, and colour emoji would ignore the lume palette and wreck
 * the ambient pixel budget.
 *
 * All of them are authored in a 100 x 100 box and scaled into place once per layout, and all of
 * them are built from clockwise sub-paths only: under the non-zero winding rule that makes the
 * result the union of the parts, which is how the ships get a superstructure and the helicopter a
 * rotor without a boolean op. The one exception is the moon, which genuinely needs a subtraction.
 *
 * ### Sized for 22 px
 * The glyph box is `0.096 r`, which is about 22 device pixels on a 454 px dial — small enough that
 * a detail thinner than four units of the authoring box disappears. The pairs that have to be told
 * apart are therefore separated by where the *mass* sits rather than by fine detail: the fast jet
 * by its delta planform against the airliner's straight wing, and the warship by a tall mast
 * amidships against the merchantman's slab and funnel hard aft.
 */
object Glyphs {

    private val scratch = Matrix()

    // The unit paths never change, so they are authored once. Rebuilding them on every call meant
    // allocating a Path — and, for the moon, running a boolean subtraction — each time the layout
    // or the planet mode changed.
    private val aircraft: Path by lazy(LazyThreadSafetyMode.NONE) { aircraft() }
    private val fighter: Path by lazy(LazyThreadSafetyMode.NONE) { fighter() }
    private val helicopter: Path by lazy(LazyThreadSafetyMode.NONE) { helicopter() }
    private val helicopterMilitary: Path by lazy(LazyThreadSafetyMode.NONE) { helicopterMilitary() }
    private val merchantShip: Path by lazy(LazyThreadSafetyMode.NONE) { merchantShip() }
    private val warship: Path by lazy(LazyThreadSafetyMode.NONE) { warship() }
    private val rocket: Path by lazy(LazyThreadSafetyMode.NONE) { rocket() }
    private val heart: Path by lazy(LazyThreadSafetyMode.NONE) { heart() }
    private val pedestrian: Path by lazy(LazyThreadSafetyMode.NONE) { pedestrian() }
    private val earthSymbol: Path by lazy(LazyThreadSafetyMode.NONE) { earthSymbol() }
    private val marsSymbol: Path by lazy(LazyThreadSafetyMode.NONE) { marsSymbol() }
    private val moonSymbol: Path by lazy(LazyThreadSafetyMode.NONE) { moonSymbol() }

    fun buildAircraft(box: RectF, out: Path) = build(aircraft, box, out)

    fun buildFighter(box: RectF, out: Path) = build(fighter, box, out)

    fun buildHelicopter(box: RectF, out: Path) = build(helicopter, box, out)

    fun buildHelicopterMilitary(box: RectF, out: Path) = build(helicopterMilitary, box, out)

    fun buildMerchantShip(box: RectF, out: Path) = build(merchantShip, box, out)

    fun buildWarship(box: RectF, out: Path) = build(warship, box, out)

    fun buildRocket(box: RectF, out: Path) = build(rocket, box, out)

    fun buildHeart(box: RectF, out: Path) = build(heart, box, out)

    fun buildPedestrian(box: RectF, out: Path) = build(pedestrian, box, out)

    fun buildEarthSymbol(box: RectF, out: Path) = build(earthSymbol, box, out)

    fun buildMarsSymbol(box: RectF, out: Path) = build(marsSymbol, box, out)

    /**
     * The crescent is authored as a half-rim sliver, so unlike the other glyphs it does not fill the
     * unit box: mapped through it, the moon would come out a quarter of the width of the others. It
     * is fitted from its own bounds instead, stretched to the target box. The horizontal stretch is
     * deliberate — a sickle drawn from true circles is either fat or hair-thin, and stretching a
     * thin one is what makes it bold and unmistakable at 24 px.
     */
    fun buildMoonSymbol(box: RectF, out: Path) {
        scratch.reset()
        scratch.setRectToRect(MOON_BOUNDS, box, Matrix.ScaleToFit.FILL)
        out.reset()
        moonSymbol.transform(scratch, out)
    }

    private fun build(unit: Path, box: RectF, out: Path) {
        scratch.reset()
        scratch.setRectToRect(UNIT_BOX, box, Matrix.ScaleToFit.CENTER)
        out.reset()
        unit.transform(scratch, out)
    }

    /** Airliner, plan view, nose up: straight wing, long fuselage, swept tailplane. */
    private fun aircraft(): Path = Path().apply {
        moveTo(50f, 2f)
        cubicTo(56f, 10f, 58f, 22f, 58f, 36f)
        lineTo(96f, 60f)
        lineTo(96f, 70f)
        lineTo(58f, 58f)
        lineTo(58f, 78f)
        lineTo(72f, 90f)
        lineTo(72f, 97f)
        lineTo(50f, 90f)
        lineTo(28f, 97f)
        lineTo(28f, 90f)
        lineTo(42f, 78f)
        lineTo(42f, 58f)
        lineTo(4f, 70f)
        lineTo(4f, 60f)
        lineTo(42f, 36f)
        cubicTo(42f, 22f, 44f, 10f, 50f, 2f)
        close()
    }

    /** Fast jet, plan view, nose up: slim fuselage, delta wing, canted tails. */
    private fun fighter(): Path = Path().apply {
        moveTo(50f, 2f)
        lineTo(57f, 22f)
        lineTo(58f, 52f)
        lineTo(58f, 84f)
        lineTo(42f, 84f)
        lineTo(42f, 52f)
        lineTo(43f, 22f)
        close()
        moveTo(50f, 26f)
        lineTo(95f, 86f)
        lineTo(95f, 94f)
        lineTo(50f, 80f)
        lineTo(5f, 94f)
        lineTo(5f, 86f)
        close()
        moveTo(50f, 88f)
        lineTo(38f, 99f)
        lineTo(30f, 96f)
        lineTo(42f, 80f)
        close()
        moveTo(58f, 80f)
        lineTo(70f, 96f)
        lineTo(62f, 99f)
        lineTo(50f, 88f)
        close()
    }

    /**
     * Civil rotorcraft, plan view: rotor disc over a single rounded pod.
     *
     * The pod is compact and round on purpose -- it is what separates this from the military
     * variant, which hangs a wide straight wing across the same rotor.
     */
    private fun helicopter(): Path = Path().apply {
        // Rotor disc: two blades, and the most recognisable part at any size.
        moveTo(6.15f, 1.55f)
        lineTo(97.51f, 42.23f)
        lineTo(93.85f, 50.45f)
        lineTo(2.49f, 9.77f)
        close()
        moveTo(2.49f, 42.23f)
        lineTo(93.85f, 1.55f)
        lineTo(97.51f, 9.77f)
        lineTo(6.15f, 50.45f)
        close()
        moveTo(45f, 26f)
        lineTo(55f, 26f)
        lineTo(55f, 40f)
        lineTo(45f, 40f)
        close()
        // Cabin pod, rounded and compact.
        addOval(39f, 38f, 61f, 66f, Path.Direction.CW)
        // Tail boom and fin.
        moveTo(46f, 60f)
        lineTo(54f, 60f)
        lineTo(54f, 86f)
        lineTo(46f, 86f)
        close()
        moveTo(50f, 76f)
        lineTo(62f, 96f)
        lineTo(38f, 96f)
        close()
    }

    /**
     * Warship in profile, bow right: a lean hull, a tall mast amidships, a gun house forward.
     *
     * The mast is the whole glyph at small sizes. Everything else is kept low and sparse so
     * that one vertical spike is the thing the eye lands on, against the merchantman's block.
     */
    private fun warship(): Path = Path().apply {
        // Lean hull with a long raked bow.
        moveTo(4f, 60f)
        lineTo(98f, 60f)
        lineTo(76f, 79f)
        lineTo(4f, 79f)
        close()
        // Low superstructure -- deliberately not a block.
        moveTo(30f, 50f)
        lineTo(50f, 50f)
        lineTo(50f, 61f)
        lineTo(30f, 61f)
        close()
        // The mast: thin, and taller than anything else in the set.
        moveTo(36f, 3f)
        lineTo(44f, 3f)
        lineTo(44f, 51f)
        lineTo(36f, 51f)
        close()
        // Forward gun house.
        moveTo(57f, 51f)
        lineTo(70f, 51f)
        lineTo(70f, 61f)
        lineTo(57f, 61f)
        close()
    }

    /**
     * Merchantman in profile, bow right: a long flush deck with one blunt mass hard aft.
     *
     * Deliberately the opposite shape to the warship rather than merely a different one. At
     * 22 px the pair are told apart by where the weight sits and what it looks like -- a solid
     * block at the stern here, a bare hull and a thin mast amidships there -- because nothing
     * finer than that survives.
     */
    /**
     * Heart, for the pulse slot.
     *
     * One closed outline rather than the usual union of blocks, because a heart is the one shape
     * here everybody already knows: at slot size the reader is matching a silhouette from memory,
     * not resolving detail, and anything that is not immediately heart-shaped is worse than the
     * two letters it replaced. The lobes are deliberately wide and the point short — a tall narrow
     * heart turns into a spade below about fifteen pixels.
     */
    private fun heart(): Path = Path().apply {
        moveTo(50f, 92f)
        cubicTo(20f, 68f, 3f, 53f, 3f, 34f)
        cubicTo(3f, 17f, 18f, 8f, 31f, 8f)
        cubicTo(41f, 8f, 47f, 15f, 50f, 22f)
        cubicTo(53f, 15f, 59f, 8f, 69f, 8f)
        cubicTo(82f, 8f, 97f, 17f, 97f, 34f)
        cubicTo(97f, 53f, 80f, 68f, 50f, 92f)
        close()
    }

    /**
     * Walking figure, for the step slot.
     *
     * Clockwise blocks again, so the union gives the figure without a boolean op. The stride is
     * exaggerated well past a real one for the same reason the fast jet is: at this size the
     * silhouette has to be read from where the mass sits, and a figure standing straight is a
     * vertical bar.
     */
    private fun pedestrian(): Path = Path().apply {
        addCircle(52f, 14f, 13f, Path.Direction.CW)
        // Torso.
        moveTo(42f, 28f)
        lineTo(62f, 28f)
        lineTo(60f, 58f)
        lineTo(40f, 58f)
        close()
        // Leading leg, thrown forward.
        moveTo(49f, 52f)
        lineTo(64f, 52f)
        lineTo(82f, 90f)
        lineTo(67f, 97f)
        close()
        // Trailing leg.
        moveTo(37f, 52f)
        lineTo(53f, 52f)
        lineTo(38f, 93f)
        lineTo(23f, 88f)
        close()
        // Leading arm, swung back to balance the leg.
        moveTo(24f, 32f)
        lineTo(40f, 27f)
        lineTo(48f, 52f)
        lineTo(33f, 57f)
        close()
    }

    private fun merchantShip(): Path = Path().apply {
        // Hull: long and low, the deck almost entirely empty.
        moveTo(2f, 62f)
        lineTo(98f, 62f)
        lineTo(88f, 79f)
        lineTo(2f, 79f)
        close()
        // Superstructure, blunt and wide, right at the stern.
        moveTo(5f, 35f)
        lineTo(33f, 35f)
        lineTo(33f, 63f)
        lineTo(5f, 63f)
        close()
        // Funnel, thick enough to read as part of the same mass.
        moveTo(12f, 23f)
        lineTo(26f, 23f)
        lineTo(26f, 36f)
        lineTo(12f, 36f)
        close()
    }

    /**
     * Launch complex: a vehicle on the pad beside its service tower.
     *
     * Drawn as the *site* rather than as the vehicle, because a lone rocket is a pointed
     * body with fins at the bottom and so is a delta-wing fighter — at 22 px the two were
     * very nearly the same silhouette. The tower makes this the only asymmetric glyph in the
     * set and the ground line the only one that sits on anything, which is what tells them
     * apart at a glance.
     */
    private fun rocket(): Path = Path().apply {
        // Vehicle on the pad: conical nose, long body.
        moveTo(32f, 2f)
        lineTo(42f, 26f)
        lineTo(42f, 78f)
        lineTo(22f, 78f)
        lineTo(22f, 26f)
        close()
        // Fins.
        moveTo(22f, 76f)
        lineTo(10f, 84f)
        lineTo(22f, 58f)
        close()
        moveTo(42f, 58f)
        lineTo(54f, 84f)
        lineTo(42f, 76f)
        close()
        // Service tower alongside — the asymmetry is the whole point at this size.
        moveTo(64f, 6f)
        lineTo(76f, 6f)
        lineTo(76f, 84f)
        lineTo(64f, 84f)
        close()
        // Access arms reaching to the vehicle.
        moveTo(52f, 20f)
        lineTo(76f, 20f)
        lineTo(76f, 27f)
        lineTo(52f, 27f)
        close()
        moveTo(52f, 42f)
        lineTo(76f, 42f)
        lineTo(76f, 49f)
        lineTo(52f, 49f)
        close()
        moveTo(52f, 64f)
        lineTo(76f, 64f)
        lineTo(76f, 71f)
        lineTo(52f, 71f)
        close()
        // Ground line: nothing else in the set has one.
        moveTo(4f, 84f)
        lineTo(96f, 84f)
        lineTo(96f, 94f)
        lineTo(4f, 94f)
        close()
    }

    /**
     * Military rotorcraft: the same rotor over a slim body carrying stub wings and pylons.
     *
     * The wings are what does the work. A rotor disc dominates any helicopter glyph, so the
     * difference has to be underneath it and has to be wide: a straight bar with weight at both
     * ends reads as armed where the civil pod reads as a bubble.
     */
    private fun helicopterMilitary(): Path = Path().apply {
        // Rotor disc, shared with the civil variant so the family still reads.
        moveTo(6.15f, 1.55f)
        lineTo(97.51f, 42.23f)
        lineTo(93.85f, 50.45f)
        lineTo(2.49f, 9.77f)
        close()
        moveTo(2.49f, 42.23f)
        lineTo(93.85f, 1.55f)
        lineTo(97.51f, 9.77f)
        lineTo(6.15f, 50.45f)
        close()
        moveTo(45f, 26f)
        lineTo(55f, 26f)
        lineTo(55f, 40f)
        lineTo(45f, 40f)
        close()
        // Slim gunship body.
        moveTo(45f, 34f)
        lineTo(55f, 34f)
        lineTo(57f, 54f)
        lineTo(55f, 72f)
        lineTo(45f, 72f)
        lineTo(43f, 54f)
        close()
        // Stub wings, wide and thin.
        moveTo(14f, 51f)
        lineTo(86f, 51f)
        lineTo(86f, 60f)
        lineTo(14f, 60f)
        close()
        // Pylons at the wingtips.
        moveTo(14f, 45f)
        lineTo(24f, 45f)
        lineTo(24f, 66f)
        lineTo(14f, 66f)
        close()
        moveTo(76f, 45f)
        lineTo(86f, 45f)
        lineTo(86f, 66f)
        lineTo(76f, 66f)
        close()
        // Tail boom and fin.
        moveTo(46f, 66f)
        lineTo(54f, 66f)
        lineTo(54f, 86f)
        lineTo(46f, 86f)
        close()
        moveTo(50f, 78f)
        lineTo(63f, 97f)
        lineTo(37f, 97f)
        close()
    }

    /**
     * Earth ♁ — a ringed circle quartered by a cross.
     *
     * The rings rely on winding: an outer clockwise circle plus an inner counter-clockwise one
     * cancel to a hollow band, and the bars drawn over the hole fill it back in.
     */
    private fun earthSymbol(): Path = Path().apply {
        addCircle(50f, 50f, 40f, Path.Direction.CW)
        addCircle(50f, 50f, 31f, Path.Direction.CCW)
        addRect(45f, 6f, 55f, 94f, Path.Direction.CW)
        addRect(6f, 45f, 94f, 55f, Path.Direction.CW)
    }

    /** Mars ♂ — a ringed circle with an arrow off the upper right. */
    private fun marsSymbol(): Path = Path().apply {
        addCircle(38f, 62f, 32f, Path.Direction.CW)
        addCircle(38f, 62f, 23f, Path.Direction.CCW)
        // Shaft, a rectangle rotated onto the 45 degree diagonal.
        moveTo(52.8f, 44.8f)
        lineTo(59.2f, 51.2f)
        lineTo(91.2f, 19.2f)
        lineTo(84.8f, 12.8f)
        close()
        // Arrowhead.
        moveTo(96f, 4f)
        lineTo(64f, 9f)
        lineTo(91f, 36f)
        close()
    }

    /**
     * Moon ☾ — a thin sickle with its horns to the right.
     *
     * A disc cut by a larger circle offset to the right leaves the left limb. Two numbers set the
     * shape: the cut circle's radius decides where the horns land, and the leftover thickness works
     * out as `rOuter - rCut + distance`. Here `rCut = sqrt(44² + 12²)` puts the horns exactly on
     * the vertical diameter — a true half-rim crescent rather than the near-closed ring a smaller
     * cutter produces — and the sliver comes out about 10 units of an 88-unit disc.
     */
    private fun moonSymbol(): Path {
        val disc = Path().apply { addCircle(52f, 50f, 44f, Path.Direction.CW) }
        val cutter = Path().apply { addCircle(64f, 50f, 45.6f, Path.Direction.CW) }
        // A real boolean subtraction, not a counter-clockwise sub-path. The cutter is larger than
        // the disc and reaches past it, and under the non-zero winding rule that overhang has
        // winding -1, which still counts as filled — it came out as a second crescent facing the
        // other way, so the pair read as a cat's eye rather than as a moon.
        disc.op(cutter, Path.Op.DIFFERENCE)
        return disc
    }

    /** Bounds of the sliver [moonSymbol] actually occupies: the left half of the disc. */
    private val MOON_BOUNDS = RectF(8f, 6f, 52f, 94f)

    private val UNIT_BOX = RectF(0f, 0f, 100f, 100f)
}
