package vc.fatfukkers.service

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class NewPipeHttpDownloader : Downloader() {
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private val youtubeCookies: String? = NetscapeCookies.youtubeCookieHeader()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(request.url()))
            .timeout(Duration.ofSeconds(60))
            .header("User-Agent", USER_AGENT)

        for ((name, values) in request.headers()) {
            for (value in values) {
                builder.header(name, value)
            }
        }

        if (youtubeCookies != null && request.url().contains("youtube")) {
            builder.header("Cookie", youtubeCookies)
        }

        val body = request.dataToSend()
        val httpRequest = if (body != null) {
            builder.method(request.httpMethod(), HttpRequest.BodyPublishers.ofByteArray(body)).build()
        } else {
            builder.method(request.httpMethod(), HttpRequest.BodyPublishers.noBody()).build()
        }

        val httpResponse = client.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        if (httpResponse.statusCode() == 429) {
            throw ReCaptchaException("reCaptcha Challenge requested", request.url())
        }

        val responseHeaders = httpResponse.headers().map().mapValues { it.value.toList() }
        return Response(
            httpResponse.statusCode(),
            httpResponse.statusCode().toString(),
            responseHeaders,
            httpResponse.body(),
            request.url(),
        )
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    }
}
