/*
 * Maze Game - play randomly generated mazes in your terminal!
 * Copyright (C) 2021  SirNapkin1334 / Napkin Technologies
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package tech.napkin.games.maze

import java.util.Random

private lateinit var maze: Maze

fun main(args: Array<String>) = println("You completed the maze in ${kotlin.system.measureNanoTime {
	maze = Maze(args.firstOrNull()?.toIntOrNull().apply {
		if (this == 0 || this == 1) return@main println("You completed the maze in NaN seconds!")
	} ?: 40)
	println("\n".repeat(maze.size + 1))
	while (!maze.render()) {
		when (RawConsoleInput.readAndReset(true)) {
			'W', 'w' -> maze.moveUp()
			'A', 'a' -> maze.moveLeft()
			'S', 's' -> maze.moveDown()
			'D', 'd' -> maze.moveRight()
			'R', 'r' -> maze = Maze()
			'Q', 'q' -> return maze.cls()
//			'Z' -> break
		}
	}
	maze.cls()
} / 1000000000.0} seconds!" + " ".repeat((maze.size * 2 - 36)))


private class Maze(val size: Int = 40) {

	companion object {

		private const val PLAYER1 = ''
		private const val PLAYER2 = ''

		private const val GOAL1 = '┣'
		private const val GOAL2 = '┫'

		private val specialChars = mapOf(PLAYER1 to PLAYER2, GOAL1 to GOAL2)

		private val standardChars = charArrayOf(' ', '█')
	}

	private var completed = false

	private var x = 0
	private var y = 0

	private inner class Blocks {

		private val arr: Array<CharArray>

		init {
			val rand = Random()
			arr = Array(size) { CharArray(size) {
				if (rand.nextDouble() > 0.7) '\u2588' else ' '
			} }
			val v = rand.nextInt(size)
			set(v, if (v == x) rand.notAt(size, y) else rand.nextInt(size), GOAL1)
			set(x, y, PLAYER1)
		}

		private fun Random.notAt(bound: Int, unwanted: Int): Int {
//			if (bound == 1 && (unwanted == 0 || unwanted == 1)) return 0

			while (true) {
				nextInt(bound).also { if (it != unwanted) return it }
			}
		}

		operator fun get(x: Int, y: Int): Char = arr[y][x]

		operator fun set(x: Int, y: Int, value: Char) { arr[y][x] = value }

		/**
		 * The reason we have all this complicated logic to print characters twice or use
		 * their counterpart, is that we're using [CharArray]s here. If we were to use
		 * [Array]<[String]>, the memory taken up by the data would increase by approx 20x.
		 */
		fun render() {
			println('┏' + "━".repeat(size * 2) + '┓')
			for (element in arr) {
				println('┃' + buildString(arr.size * 2) {
					element.forEach {
						append(it)
						(if (it in standardChars) it else specialChars[it] ?: throw IllegalStateException(
							"Character that is both non-standard and non-special: '$it'")).also(::append)
					}
				} + '┃')
			}
			println('┗' + "\u2501".repeat(size * 2) + '┛')
		}

	}

	val blocks = Blocks()

	// https://i.imgur.com/NblyBeU.png
	// https://i.imgur.com/bg0EUP1.png
	/** @return whether or not the game is completed */
	fun render() = completed.apply {
		cls()
		blocks.render()
	}

	fun moveLeft() {
		if (x > 0 && check(x - 1, y)) {
			blocks[x--, y] = ' '
			blocks[x, y] = PLAYER1
		}
	}

	fun moveRight() {
		if (x < size - 1 && check(x + 1, y)) {
			blocks[x++, y] = ' '
			blocks[x, y] = PLAYER1
		}
	}

	fun moveUp() {
		if (y > 0 && check(x, y - 1)) {
			blocks[x, y--] = ' '
			blocks[x, y] = PLAYER1
		}
	}

	fun moveDown() {
		if (y < size - 1 && check(x, y + 1)) {
			blocks[x, y++] = ' '
			blocks[x, y] = PLAYER1
		}
	}

	/** @return if the space can be moved into */
	private fun check(x: Int, y: Int): Boolean = if (blocks[x, y] == GOAL1) {
		completed = true
		true
	} else blocks[x, y] == ' '

	// " " repetitions are one more than they should be because sometimes random
	// characters appear to the right of the game board
	fun cls() = print((" ".repeat(size * 2 + 3) + "\u001b[1F").repeat(size + 2))

}
