package com.satya.musicplayer

class UtilsTest {
   @org.junit.Test
   fun testA() {
      val list = Utils.parseTimestampCommands(
          """
00:00:00: SaReMa
00:00:13: stop 30 SaMaRe
00:00:16: stop 30 ReSaMa
00:00:19: stop 30 ReMaSa
00:00:23: stop 30 MaSaRe
00:00:26: stop 30 MaReSa
00:00:29: stop 
00:01:08: stop 30 SaGaMa
00:01:11: stop 30 SaMaGa
00:01:16: stop 30 GaSaMa
00:01:18: stop 30 GaMaSa
00:01:26: stop 30 MaSaGa
00:01:28: stop 30 MaGaSa
00:01:31: stop
00:02:46: stop 30 ReGaMa
00:02:49: stop 30 MaGaRe
00:02:53: stop 30 GaReMa
00:02:56: stop 30 GaMaRe
00:03:00: stop 30 MaReGa
00:03:00: stop 30 MaReGa
00:03:04: stop 30 MaGaRe

      """.trimIndent()
      )
      println(list)
   }
}
