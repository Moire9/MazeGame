/*
 * Copyright 2015 Christian d'Heureuse, Inventec Informatik AG, Zurich, Switzerland
 * www.source-code.biz, www.inventec.ch/chdh
 *
 * Multi-licensed under the GNU Lesser General Public License v2.1 or later, and
 * the Eclipse Public License, V1.0 or later.
 *
 * Significant modifications done, however the original logic is still used.
 *
 * https://www.source-code.biz/snippets/java/RawConsoleInput/RawConsoleInput.java
 */

@file:Suppress("SpellCheckingInspection")

package tech.napkin.games.maze

import com.sun.jna.*
import com.sun.jna.ptr.IntByReference
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.*
import kotlin.reflect.KClass

/**
 * A JNA based driver for reading single characters from the console.
 *
 * This class is used for console mode programs.
 * It supports non-blocking reads of single key strokes without echo.
 */
internal object RawConsoleInput {

	private val LOCK = Any()

	private val isWindows = System.getProperty("os.name").startsWith("Windows")
	private const val invalidKey = 0xFFFE
	private const val invalidKeyStr = invalidKey.toChar().toString()
	private const val stdinFd = 0
	private val stdinIsConsole: Boolean
	private var consoleModeAltered = false

	// Windows
	private val consoleHandle: Pointer
	private val originalConsoleMode: Int

	// Unix
	private val charsetDecoder = Charset.defaultCharset().newDecoder()
	private val originalTermios: Unix.Termios
	private val rawTermios: Unix.Termios
	private val intermediateTermios: Unix.Termios

	init {
		synchronized(LOCK) {
			if (isWindows) {
				val (ch, ocm, sic) = try {
					Windows.getStdHandle(Windows.Kernel32Defs.STD_INPUT_HANDLE).let {
						Triple(it, Windows.getConsoleMode(it), true)
					}
				} catch (e: IOException) {
					// first two won't be used
					Triple(Pointer(0), -1, false)
				}
				stdinIsConsole = sic
				consoleHandle = ch
				originalConsoleMode = ocm
				if (stdinIsConsole) registerShutdownHook()

				// these won't be used
				originalTermios = Unix.NULL_TERM
				rawTermios = Unix.NULL_TERM
				intermediateTermios = Unix.NULL_TERM
			} else {
				stdinIsConsole = Unix.isFDTerminal(stdinFd)
				if (stdinIsConsole) {
					originalTermios = Unix.Termios().getAttr(stdinFd)
					rawTermios = Unix.Termios(originalTermios.c_lflag and
						(Unix.Defs.ICANON or Unix.Defs.ECHO or Unix.Defs.ECHONL or Unix.Defs.ISIG).inv(),
						originalTermios.filler)

					intermediateTermios = Unix.Termios(rawTermios.c_lflag or Unix.Defs.ICANON, rawTermios.filler)

					// Canonical mode can be switched off between the read() calls, but echo must remain disabled.
					registerShutdownHook()
				} else {
					originalTermios = Unix.NULL_TERM
					rawTermios = Unix.NULL_TERM
					intermediateTermios = Unix.NULL_TERM
				}

				// these won't be used
				consoleHandle = Pointer(0)
				originalConsoleMode = -1
			}
		}
	}

	/**
	 * Read a character and then reset the console mode.
	 */
	fun readAndReset(wait: Boolean): Char = read(wait).also { resetConsoleMode() }

	/**
	 * Reads a character from the console without echo.
	 *
	 * @param[wait] `true` to wait until an input character is available,
	 * `false` to return immediately if no character is available.
	 * @return 0xFFFF if `wait` is `false` and no character is available.
	 * 0xFFFE on EOF.
	 * Otherwise an Unicode character code within the range 0 to 0xFFFF.
	 */
	fun read(wait: Boolean): Char = (if (isWindows) {
		if (!stdinIsConsole) {
			val c = Windows.getWideChar()
			if (c != 0xFFFF) c else 0xFFFE // EOF
		} else {
			consoleModeAltered = true
			Windows.setConsoleMode(consoleHandle, originalConsoleMode and Windows.Kernel32Defs.ENABLE_PROCESSED_INPUT.inv())
			// ENABLE_PROCESSED_INPUT must remain off to prevent Ctrl-C from being processed by the system
			// while the program is not within getwch().
			if (wait || Windows.keyPressed()) {
				Windows.getWCH().let {
					when(it) {
						0, 0xE0 -> { // Function key or arrow key
							Windows.getWCH().let { c ->
								if (c in 0..0x18FF) 0xE000 + c else invalidKey // construct key code in private Unicode range
							}
						}
						in 0..0xFFFF -> it
						else -> invalidKey
					}
				}
			} else 0xFFFF // no key available
		}
	} else if (stdinIsConsole) {
		consoleModeAltered = true
		rawTermios.setMode() // switch off canonical mode, echo and signals
		try {
			if (wait || System.`in`.available() != 0) readCharFromSysIn() else 0xFFFF // no key available
		} finally { // reset some console attributes
			intermediateTermios.setMode()
		}
	} else readCharFromSysIn()).toChar() // we can't read from non-consoles

	/**
	 * Resets console mode to normal line mode with echo.
	 *
	 *
	 * On Windows this method re-enables Ctrl-C processing.
	 *
	 *
	 * On Unix this method switches the console back to echo mode.
	 * read() leaves the console in non-echo mode.
	 */
	fun resetConsoleMode() {
		if (stdinIsConsole && consoleModeAltered) {
			if (isWindows) Windows.setConsoleMode(consoleHandle, originalConsoleMode)
			else originalTermios.setMode()
			consoleModeAltered = false
		}
	}

	private fun registerShutdownHook() = Runtime.getRuntime().addShutdownHook(Thread { resetConsoleMode() })

	//--- Unix ---------------------------------------------------------------------

	// The Unix version uses tcsetattr() to switch the console to non-canonical mode,
	// System.in.available() to check whether data is available and System.in.read()
	// to read bytes from the console.
	// A CharsetDecoder is used to convert bytes to characters.

	private fun readCharFromSysIn(): Int {
		val inBuf = ByteArray(4)
		var inLen = 0
		while (true) {
			if (inLen >= inBuf.size) return invalidKey // input buffer overflow
			val b = System.`in`.read() // read next byte
			if (b == -1) return -1 // EOF
			inBuf[inLen++] = b.toByte()

			// Synchronized because the charsetDecoder must only be used by a single thread at once.
			val c = synchronized(LOCK) {
				val out = CharBuffer.allocate(1)
				charsetDecoder.apply {
					reset()
					onMalformedInput(CodingErrorAction.REPLACE)
					replaceWith(invalidKeyStr)
					decode(ByteBuffer.wrap(inBuf, 0, inLen), out, false)
				}
				if (out.position() != 0) out[0].toInt() else -1
			}
			if (c != -1) return c
		}
	}

	private object Unix {

		val NULL_TERM = Termios(0, ByteArray(0))

		// termios.h
		@Suppress("PropertyName", "unused")
		@Structure.FieldOrder("c_iflag", "c_oflag", "c_cflag", "c_lflag", "filler")
		class Termios(
			@JvmField val c_lflag: Int = 0,
			@JvmField var filler: ByteArray = ByteArray(64) // actual length is platform dependent
		) : Structure() {

			// These are never used by anything, but the code won't work unless they exist.
			@JvmField val c_iflag = 0
			@JvmField val c_oflag = 0
			@JvmField val c_cflag = 0

			fun clone() = Termios(c_lflag, filler.clone())

			fun getAttr(fd: Int): Termios = apply {
				try {
					libc!!.tcgetattr(fd, this).also {
						if (it != 0) throw RuntimeException("tcgetattr() failed. (exit code $it)")
					}
				} catch (e: LastErrorException) {
					throw IOException("tcgetattr() errored. (exit code ${e.errorCode})", e)
				}
			}

			fun setAttr(fd: Int, opt: Int) {
				libc!!.tcsetattr(fd, opt, this).also {
					if (it != 0) throw RuntimeException("tcsetattr() failed. (code $it)")
				}
			}

			fun setMode() = setAttr(stdinFd, Defs.TCSANOW)
		}

		// todo move termius in here and make this private
		private val libc = if (!isWindows) Native.load("c", Libc::class.java) else null
			get() = field ?: throw IllegalStateException("Not on Unix.")

		@Suppress("SpellCheckingInspection")
		interface Libc : Library {

			// termios.h
			fun tcgetattr(fd: Int, termios: Termios): Int

			@Throws(LastErrorException::class)
			fun tcsetattr(fd: Int, opt: Int, termios: Termios): Int

			// unistd.h
			fun isatty(fd: Int): Int
		}

		fun isFDTerminal(fd: Int) = libc!!.isatty(fd) == 1

		object Defs {
			// termios.h
			const val ISIG = 1
			const val ICANON = 2
			const val ECHO = 8
			const val ECHONL = 64
			const val TCSANOW = 0
		}

	}

	private object Windows {

		private fun <T : Library> loadWin(name: String, interfaceClass: KClass<T>) = if (isWindows) Native.load(name, interfaceClass.java) else null

		private val msvcrt = loadWin("msvcrt", Msvcrt::class)
			get() = field ?: throw IllegalStateException("Not on Windows.")

		private val kernel32 = loadWin("kernel32", Kernel32::class)
			get() = field ?: throw IllegalStateException("Not on Windows.")

		@Suppress("SpellCheckingInspection", "FunctionName")
		private interface Msvcrt : Library {
			fun _kbhit(): Int
			fun _getwch(): Int
			fun getwchar(): Int
		}

		fun keyPressed() = msvcrt!!._kbhit() != 0
		fun getWCH() = msvcrt!!._getwch()
		fun getWideChar() = msvcrt!!.getwchar()

		@Suppress("FunctionName")
		private interface Kernel32: Library {
			fun SetConsoleMode(hConsoleHandle: Pointer, dwMode: Int): Int
			fun GetConsoleMode(hConsoleHandle: Pointer, lpMode: IntByReference): Int
			fun GetStdHandle(nStdHandle: Int): Pointer
		}

		/** Returns the console mode of the console handle pointed to by the given pointer. */
		fun getConsoleMode(hConsoleHandle: Pointer): Int = IntByReference().also {
			if (kernel32!!.GetConsoleMode(hConsoleHandle, it) == 0) throw IOException("GetConsoleMode() failed.")
		}.value

		/** Set the console mode of the console handle pointed to by the given pointer. */
		fun setConsoleMode(handle: Pointer, mode: Int) {
			if (kernel32!!.SetConsoleMode(handle, mode) == 0) throw IOException("SetConsoleMode() failed.")
		}

		/** Get a pointer to the std handle at the provided location. */
		fun getStdHandle(handle: Int): Pointer {
			return kernel32!!.GetStdHandle(handle).also {
				if (Pointer.nativeValue(it) == 0L || Pointer.nativeValue(it) == Kernel32Defs.INVALID_HANDLE) {
					throw IOException("GetStdHandle(STD_INPUT_HANDLE) failed.")
				}
			}
		}

		object Kernel32Defs {
			const val STD_INPUT_HANDLE = -10
			val INVALID_HANDLE = if (Native.POINTER_SIZE == 8) -1 else 0xFFFFFFFFL
			const val ENABLE_PROCESSED_INPUT = 0x0001
//			const val ENABLE_LINE_INPUT = 0x0002
//			const val ENABLE_ECHO_INPUT = 0x0004
//			const val ENABLE_WINDOW_INPUT = 0x0008
		}
	}

}
