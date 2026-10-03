package io.github.meepdong.talaria.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LONG_DAY = DateTimeFormatter.ofPattern("EEEE d MMMM").withZone(ZoneId.systemDefault())

/** "Monday 5 October". */
fun longDate(nowMs: Long): String = LONG_DAY.format(Instant.ofEpochMilli(nowMs))

/** Home: the date and the latest chats. */
fun homeView(chat: ChatView, nowMs: Long): HomeView =
    HomeView(date = longDate(nowMs), recent = chat.conversations.take(RECENT_CHATS))

/** The ☰ panel: replies at work, provider balances and the connection rows. */
fun menuView(chat: ChatView, status: StatusView): MenuView = MenuView(
    running = chat.conversations.filter { it.running }.map { RunningItem(it.title, "Hermes is replying", it.id) },
    balances = status.balances,
    connection = status.rows,
)

const val RECENT_CHATS = 5
