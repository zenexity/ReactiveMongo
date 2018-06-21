import org.specs2.concurrent.ExecutionEnv

class UtilSpec(implicit ee: ExecutionEnv)
  extends org.specs2.mutable.Specification {

  "Utilities" title

  section("unit")
  "URI" should {
    import scala.io.Source

    "be loaded from a local file" in {
      val resource = new java.io.File("/etc/hosts")

      reactivemongo.api.tests.withContent(resource.toURI) { in =>
        Source.fromInputStream(in).mkString must beTypedEqualTo(
          Source.fromFile(resource).mkString)
      }
    }

    "be loaded from classpath" in {
      val resource = getClass.getResource("/reference.conf")

      reactivemongo.api.tests.withContent(resource.toURI) { in =>
        Source.fromInputStream(in).mkString must beTypedEqualTo(
          Source.fromURL(resource).mkString)
      }
    }
  }
  section("unit")

  "DNS resolver" should {
    "resolve SRV record for _imaps._tcp at gmail.com" in {
      reactivemongo.util.srvRecords(
        name = "gmail.com",
        srvPrefix = "_imaps._tcp") must beTypedEqualTo(List(
        "imap.gmail.com" -> 993)).
        await

    } tag "wip"

    "resolve TXT record for gmail.com" in {
      reactivemongo.util.txtRecords(
        "gmail.com") must beTypedEqualTo(
          List("v=spf1 redirect=_spf.google.com")).await

    } tag "wip"
  }
}
