package fpuwrapper.hardfloat

import chisel3._
import chisel3.experimental._
import chisel3.util._

object FMAOp extends ChiselEnum {
  // 1 * op[1] + op[2]
  val FADD = Value
  // 1 * op[1] - op[2]
  val FSUB = Value
  // op[0] * op[1] + 0
  val FMUL = Value
  // op[0] * op[1] + op[2]
  val FMADD = Value
  // op[0] * op[1] - op[2]
  val FMSUB = Value
  // -(op[0] * op[1] - op[2])
  val FNMSUB = Value
  // -(op[0] * op[1] + op[2])
  val FNMADD = Value

  val NOP = FADD
}

// https://github.com/chipsalliance/rocket-chip/blob/master/src/main/scala/tile/FPU.scala
// with modifications of extra stages
class MulAddRecFNPipe(latency: Int, expWidth: Int, sigWidth: Int)
    extends Module {
  require(latency <= 2)

  val io = IO(new Bundle {
    val validin = Input(Bool())
    val op = Input(Bits(2.W))
    val a = Input(Bits((expWidth + sigWidth + 1).W))
    val b = Input(Bits((expWidth + sigWidth + 1).W))
    val c = Input(Bits((expWidth + sigWidth + 1).W))
    val roundingMode = Input(UInt(3.W))
    val detectTininess = Input(UInt(1.W))
    val out = Output(Bits((expWidth + sigWidth + 1).W))
    val exceptionFlags = Output(Bits(5.W))
    val validout = Output(Bool())
  })

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------

  val mulAddRecFNToRaw_preMul = Module(
    new _root_.hardfloat.MulAddRecFNToRaw_preMul(expWidth, sigWidth)
  )
  val mulAddRecFNToRaw_postMul = Module(
    new _root_.hardfloat.MulAddRecFNToRaw_postMul(expWidth, sigWidth)
  )

  mulAddRecFNToRaw_preMul.io.op := io.op
  mulAddRecFNToRaw_preMul.io.a := io.a
  mulAddRecFNToRaw_preMul.io.b := io.b
  mulAddRecFNToRaw_preMul.io.c := io.c

  val mulAddResult =
    (mulAddRecFNToRaw_preMul.io.mulAddA *
      mulAddRecFNToRaw_preMul.io.mulAddB) +&
      mulAddRecFNToRaw_preMul.io.mulAddC

  val valid_stage0 = Wire(Bool())
  val roundingMode_stage0 = Wire(UInt(3.W))
  val detectTininess_stage0 = Wire(UInt(1.W))

  val postmul_regs = if (latency > 0) 1 else 0
  mulAddRecFNToRaw_postMul.io.fromPreMul := Pipe(
    io.validin,
    mulAddRecFNToRaw_preMul.io.toPostMul,
    postmul_regs
  ).bits
  mulAddRecFNToRaw_postMul.io.mulAddResult := Pipe(
    io.validin,
    mulAddResult,
    postmul_regs
  ).bits
  mulAddRecFNToRaw_postMul.io.roundingMode := Pipe(
    io.validin,
    io.roundingMode,
    postmul_regs
  ).bits
  roundingMode_stage0 := Pipe(io.validin, io.roundingMode, postmul_regs).bits
  detectTininess_stage0 := Pipe(
    io.validin,
    io.detectTininess,
    postmul_regs
  ).bits
  valid_stage0 := Pipe(io.validin, false.B, postmul_regs).valid

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------

  val roundRawFNToRecFN = Module(
    new _root_.hardfloat.RoundRawFNToRecFN(expWidth, sigWidth, 0)
  )

  val round_regs = if (latency == 2) 1 else 0
  roundRawFNToRecFN.io.invalidExc := Pipe(
    valid_stage0,
    mulAddRecFNToRaw_postMul.io.invalidExc,
    round_regs
  ).bits
  roundRawFNToRecFN.io.in := Pipe(
    valid_stage0,
    mulAddRecFNToRaw_postMul.io.rawOut,
    round_regs
  ).bits
  roundRawFNToRecFN.io.roundingMode := Pipe(
    valid_stage0,
    roundingMode_stage0,
    round_regs
  ).bits
  roundRawFNToRecFN.io.detectTininess := Pipe(
    valid_stage0,
    detectTininess_stage0,
    round_regs
  ).bits
  io.validout := Pipe(valid_stage0, false.B, round_regs).valid

  roundRawFNToRecFN.io.infiniteExc := false.B

  io.out := roundRawFNToRecFN.io.out
  io.exceptionFlags := roundRawFNToRecFN.io.exceptionFlags
}
