package com.elad.faceblur.track

/** What the blur effect draws in a frame, looked up by the frame's presentation time. */
fun interface FaceSource {
    fun facesAt(tUs: Long): List<FaceShape>
}
