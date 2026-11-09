package app.pimobile.ui.sessions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderBrowserTest {

    private fun body(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun parsesAFolderListing() {
        val listing = FolderBrowser.parse(
            body(
                """{"path":"/home/me","parentPath":"/home","directories":[""" +
                    """{"name":".cache","path":"/home/me/.cache"},{"name":"work","path":"/home/me/work"},{"name":"no path"}]}""",
            ),
        )!!
        assertEquals("/home/me", listing.path)
        assertEquals(listOf("/home/me/.cache", "/home/me/work"), listing.folders.map { it.path })
        assertEquals(listOf(".cache"), listing.folders.filter(FolderBrowser::isHidden).map { it.name })
    }

    @Test
    fun aWindowsDriveListIsAListingWithNoPath() {
        val listing = FolderBrowser.parse(
            body("""{"path":"","parentPath":null,"drives":[{"name":"C:","path":"C:\\"}],"directories":[]}"""),
        )!!
        assertEquals("", listing.path)
        assertEquals(listOf(ServerFolder("C:", "C:\\")), listing.folders)
    }

    @Test
    fun anErrorIsNoListing() {
        assertNull(FolderBrowser.parse(body("""{"error":"Directory does not exist"}""")))
    }

    @Test
    fun posixCrumbsStartAtTheRoot() {
        assertEquals(listOf(FolderCrumb("/", "/")), FolderBrowser.crumbs("/"))
        assertEquals(
            listOf(FolderCrumb("/", "/"), FolderCrumb("/home", "home"), FolderCrumb("/home/me", "me")),
            FolderBrowser.crumbs("/home/me"),
        )
    }

    @Test
    fun windowsCrumbsStartAtTheDriveList() {
        assertEquals(listOf(FolderCrumb(null, "Drives")), FolderBrowser.crumbs(""))
        assertEquals(listOf(FolderCrumb(null, "Drives"), FolderCrumb("C:\\", "C:")), FolderBrowser.crumbs("C:\\"))
        assertEquals(
            listOf(
                FolderCrumb(null, "Drives"),
                FolderCrumb("C:\\", "C:"),
                FolderCrumb("C:\\Users", "Users"),
                FolderCrumb("C:\\Users\\me", "me"),
            ),
            FolderBrowser.crumbs("C:\\Users\\me"),
        )
    }

    @Test
    fun aUncShareIsItsOwnRoot() {
        assertEquals(
            listOf(FolderCrumb("\\\\srv\\share", "srv\\share"), FolderCrumb("\\\\srv\\share\\code", "code")),
            FolderBrowser.crumbs("\\\\srv\\share\\code"),
        )
    }
}
