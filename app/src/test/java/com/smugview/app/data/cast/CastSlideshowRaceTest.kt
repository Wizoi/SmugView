package com.smugview.app.data.cast

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/**
 * Step 6-9 (R-59): the slideshow's list and index used to be touched by the timer loop, Stop and Next/Previous on different
 * threads, so a list that had just been emptied or swapped made `% 0` or an index past the end throw.
 *
 * This runs on REAL threads (Dispatchers.Default), not virtual time, because a race cannot show up in virtual time. One thread
 * starts a new slideshow over and over (which stops the old one first, so the list is empty for an instant) while another presses
 * Next and Previous. Every exception on any thread, the coroutines' included, is collected; there must be none.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CastSlideshowRaceTest {
    private val caught = CopyOnWriteArrayList<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher()) // the manager's init hops to Main; nothing here needs it to run
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> caught += e }
    }

    @After fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        Dispatchers.resetMain()
    }

    @Test fun `Next and Previous racing a slideshow that is stopped and started again throw nothing in 1000 rounds`() {
        val io = FakeCastIo().apply { dialSupported = false } // a Fire TV with the screen link: casting an image makes no network call
        val manager = DefaultCastManager(Mockito.mock(Context::class.java), Dispatchers.Default, io)
        val fireTv = CastDevice("192.168.1.60", "Fire TV", "192.168.1.60", CastType.AMAZON)
        manager.connectToDevice(fireTv)
        val connectDeadline = System.currentTimeMillis() + 10_000
        while (manager.activeDevice.value?.state != ConnectionState.CONNECTED && System.currentTimeMillis() < connectDeadline) Thread.sleep(20)
        assertEquals(ConnectionState.CONNECTED, manager.activeDevice.value?.state)

        val long = (1..5).map { "https://example.com/$it.jpg" }
        val short = listOf("https://example.com/only.jpg")
        val rounds = 1_000
        val barrier = CyclicBarrier(2)
        val starter = Thread {
            try {
                repeat(rounds) { i ->
                    barrier.await(10, TimeUnit.SECONDS)
                    repeat(10) { n -> manager.castSlideshow(if ((i + n) % 2 == 0) long else short, intervalSeconds = 2) }
                    barrier.await(10, TimeUnit.SECONDS)
                }
            } catch (e: Throwable) { caught += e }
        }
        val stepper = Thread {
            try {
                repeat(rounds) {
                    barrier.await(10, TimeUnit.SECONDS)
                    repeat(30) { n -> if (n % 2 == 0) manager.nextPhoto() else manager.previousPhoto() }
                    barrier.await(10, TimeUnit.SECONDS)
                }
            } catch (e: Throwable) { caught += e }
        }
        starter.start(); stepper.start()
        starter.join(120_000); stepper.join(120_000)
        Thread.sleep(500) // let the manager's own coroutines finish and report
        manager.disconnect()

        assertTrue("exceptions captured: ${caught.map { it.toString() }.distinct()}", caught.isEmpty())
    }
}
