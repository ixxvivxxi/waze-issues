package by.ster.wazeissues.bubble

/** Where the bubble action icons expand relative to the hub. */
enum class BubbleExpandDirection {
    Up,
    Down,
    Left,
    Right,
    ;

    companion object {
        fun fromStored(raw: String?): BubbleExpandDirection =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: Up
    }
}
