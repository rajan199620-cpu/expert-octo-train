package com.earmark.core.qa

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import com.earmark.core.model.Book
import java.time.Duration

/**
 * Answers listener questions with Claude.
 *
 * - Low effort: this is a spoken, interactive reply; latency matters more than depth.
 * - Server-side refusal fallbacks are enabled, so a safety-classifier refusal is retried on
 *   a fallback model instead of leaving the listener with silence.
 * - In full-book mode the book is sent as a 1-hour cached system block, so follow-up
 *   questions in the same listening session reuse it at cache-read prices.
 */
class ClaudeAnswerer(
    private val book: Book,
    apiKey: String,
    private val promptBuilder: QaPromptBuilder = QaPromptBuilder(book),
    private val model: String = DEFAULT_MODEL,
    /** Send the whole book (cached) instead of retrieved passages; best for short books. */
    private val fullBookContext: Boolean = false,
    baseUrl: String? = null,
    timeout: Duration = Duration.ofSeconds(60),
) : QuestionAnswerer, AutoCloseable {
    private val client: AnthropicClient? = apiKey.takeIf { it.isNotBlank() }?.let { key ->
        AnthropicOkHttpClient.builder()
            .apiKey(key)
            .maxRetries(1)
            .timeout(timeout)
            .apply { if (baseUrl != null) baseUrl(baseUrl) }
            .build()
    }

    fun buildParams(request: QaRequest): MessageCreateParams {
        val prompt = promptBuilder.build(request, fullBook = fullBookContext)
        val system = buildList {
            add(BetaTextBlockParam.builder().text(prompt.system).build())
            prompt.cachedContext?.let {
                add(
                    BetaTextBlockParam.builder()
                        .text(it)
                        .cacheControl(BetaCacheControlEphemeral.builder().ttl(BetaCacheControlEphemeral.Ttl.TTL_1H).build())
                        .build(),
                )
            }
        }
        return MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .addBeta(FALLBACK_BETA)
            .fallbacksDefault()
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            .systemOfBetaTextBlockParams(system)
            .addUserMessage(prompt.user)
            .build()
    }

    override fun answer(request: QaRequest): QaAnswer {
        val c = client ?: throw QaException(QaException.Kind.NO_KEY, "Add an Anthropic API key in Settings to ask questions.")
        val message = try {
            c.beta().messages().create(buildParams(request))
        } catch (e: UnauthorizedException) {
            throw QaException(QaException.Kind.AUTH, "Your Anthropic API key was rejected.", e)
        } catch (e: PermissionDeniedException) {
            throw QaException(QaException.Kind.AUTH, "Your Anthropic API key doesn't have access to this model.", e)
        } catch (e: RateLimitException) {
            throw QaException(QaException.Kind.RATE_LIMIT, "Too many requests right now. Try again in a moment.", e)
        } catch (e: NotFoundException) {
            throw QaException(QaException.Kind.OTHER, "The model '$model' is not available to this API key.", e)
        } catch (e: BadRequestException) {
            throw QaException(QaException.Kind.OTHER, "The request was rejected: ${e.message}", e)
        } catch (e: InternalServerException) {
            throw QaException(QaException.Kind.SERVER, "The AI service had an error. Try again.", e)
        } catch (e: AnthropicServiceException) {
            throw QaException(QaException.Kind.SERVER, "The AI service returned an error (${e.statusCode()}).", e)
        } catch (e: AnthropicIoException) {
            throw QaException(QaException.Kind.NETWORK, "Couldn't reach the AI service. Check your connection.", e)
        }

        val stop = message.stopReason().orElse(null)
        if (stop == BetaStopReason.REFUSAL) {
            throw QaException(QaException.Kind.REFUSED, "The AI declined to answer that question.")
        }
        val text = message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("").trim()
        if (text.isEmpty()) {
            throw QaException(QaException.Kind.OTHER, if (stop == BetaStopReason.MAX_TOKENS) "The answer was cut off." else "The AI returned an empty answer.")
        }
        return AnswerParser.parse(text, book.sentences.size)
    }

    override fun close() {
        client?.close()
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    }
}
