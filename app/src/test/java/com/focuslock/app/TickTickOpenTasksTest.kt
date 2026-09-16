package com.focuslock.app

import com.focuslock.app.service.TickTickApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the tolerant TickTick project-data parser behind
 * TickTickApiClient.fetchOpenTasks() / fetchCompletedTaskTitlesToday().
 *
 * No network I/O: parseProjectTasksJson is a companion-object static and the
 * tests only exercise JSON → TickTickTaskItem mapping.
 */
class TickTickOpenTasksTest {

    @Test
    fun mixedStatusListKeepsOnlyOpenTasks() {
        val body = """
            {
              "project": { "id": "p1", "name": "Work" },
              "tasks": [
                { "id": "t1", "projectId": "p1", "title": "Open A", "status": 0 },
                { "id": "t2", "projectId": "p1", "title": "Done B", "status": 2, "completedTime": "2026-09-15T09:00:00.000+0000" },
                { "id": "t3", "projectId": "p1", "title": "Open C", "status": 0 }
              ]
            }
        """.trimIndent()

        val open = TickTickApiClient.parseProjectTasksJson(body)

        assertEquals(listOf("t1", "t3"), open.map { it.id })
        assertTrue(open.all { it.status != 2 })
    }

    @Test
    fun malformedEntriesAreSkippedWithoutThrowing() {
        val body = """
            {
              "tasks": [
                "not-an-object",
                42,
                { "id": "good", "projectId": "p1", "title": "Keep me", "status": 0 },
                { "id": "done", "projectId": "p1", "title": "Drop me", "status": 2 }
              ]
            }
        """.trimIndent()

        val open = TickTickApiClient.parseProjectTasksJson(body)

        assertEquals(1, open.size)
        assertEquals("good", open.first().id)
    }

    @Test
    fun blankAndNonArrayBodiesReturnEmpty() {
        assertEquals(0, TickTickApiClient.parseProjectTasksJson("").size)
        assertEquals(0, TickTickApiClient.parseProjectTasksJson("   ").size)
        assertEquals(0, TickTickApiClient.parseProjectTasksJson("not json at all").size)
        assertEquals(0, TickTickApiClient.parseProjectTasksJson("""[1, 2, 3]""").size)
        assertEquals(0, TickTickApiClient.parseProjectTasksJson("""{ "tasks": "nope" }""").size)
        assertEquals(0, TickTickApiClient.parseProjectTasksJson("null").size)
    }

    @Test
    fun missingTitleAndProjectIdUseFallbacks_missingIdIsSkipped() {
        val body = """
            {
              "project": { "id": "fallback-project", "name": "Inbox" },
              "tasks": [
                { "id": "no-title", "status": 0 },
                { "id": "no-project", "title": "Second", "status": 0 },
                { "title": "no id at all", "status": 0 }
              ]
            }
        """.trimIndent()

        val open = TickTickApiClient.parseProjectTasksJson(body)

        // The entry without an id cannot be picked and is skipped; the other two survive.
        assertEquals(2, open.size)

        val noTitle = open.first { it.id == "no-title" }
        assertEquals("TickTick Task", noTitle.title)
        assertEquals("fallback-project", noTitle.projectId)
        assertNull(noTitle.dueDate)

        val noProject = open.first { it.id == "no-project" }
        assertEquals("Second", noProject.title)
        assertEquals("fallback-project", noProject.projectId)
        assertNull(noProject.dueDate)
    }

    @Test
    fun realisticMultiProjectSampleMergesOpenTasks() {
        val workBody = """
            {
              "project": { "id": "work", "name": "Work", "color": "#4A90D9", "closed": false },
              "tasks": [
                { "id": "w1", "projectId": "work", "title": "Ship the parser", "status": 0,
                  "dueDate": "2026-09-15T18:00:00.000+0000", "startDate": "2026-09-15T09:00:00.000+0000" },
                { "id": "w2", "projectId": "work", "title": "Old completed", "status": 2,
                  "completedTime": "2026-09-14T10:00:00.000+0000" },
                { "id": "w3", "projectId": "work", "title": "Review PR", "status": 0 }
              ]
            }
        """.trimIndent()
        val personalBody = """
            {
              "project": { "id": "personal", "name": "Personal" },
              "tasks": [
                { "id": "p1", "projectId": "personal", "title": "Buy groceries", "status": 1 },
                { "id": "p2", "projectId": "personal", "title": "Done errand", "status": 2 },
                { "id": "p3", "title": "Call dentist", "status": 0 }
              ]
            }
        """.trimIndent()

        // fetchOpenTasks concatenates per-project parses in project order, exactly like this.
        val merged = TickTickApiClient.parseProjectTasksJson(workBody) +
            TickTickApiClient.parseProjectTasksJson(personalBody)

        assertEquals(listOf("w1", "w3", "p1", "p3"), merged.map { it.id })
        assertEquals(setOf("work", "personal"), merged.map { it.projectId }.toSet())
        assertEquals("personal", merged.last().projectId) // no projectId → enclosing project.id
        assertEquals("Call dentist", merged.last().title)
    }
}
