package aman.playbackengine.enginecore

/**
 * Defines the hardware decoding strategy for the video engine.
 */
enum class DecoderPolicy {
    /** Hardware decoding with direct rendering (HW+). Max battery savings. */
    HW_PLUS,
    
    /** Hardware decoding with copy-back to RAM (HW). Best feature compatibility. */
    HW,
    
    /** Software decoding on the CPU (SW). Most stable, highest battery usage. */
    SW
}
