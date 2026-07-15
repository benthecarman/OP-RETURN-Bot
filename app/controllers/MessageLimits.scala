package controllers

import scodec.bits.ByteVector

import java.nio.charset.StandardCharsets

object MessageLimits {
  val MaxBytes: Long = 99_000L
  val MaxBytesDisplay: String = "99,000"
  val TooLongError: String =
    s"Message was too long, max length is $MaxBytesDisplay bytes"
  val Description: String =
    s"The message to write (max $MaxBytesDisplay bytes)"

  def isAllowed(message: ByteVector): Boolean = message.length <= MaxBytes

  def isAllowed(message: String): Boolean =
    isAllowed(ByteVector(message.getBytes(StandardCharsets.UTF_8)))
}
