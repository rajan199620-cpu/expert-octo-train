package com.ankiwear.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.ankiwear.wear.model.CardData
import com.ankiwear.wear.model.CardType
import com.ankiwear.wear.model.DeckInfo
import com.ankiwear.wear.screens.DeckListScreen
import com.ankiwear.wear.screens.DisconnectedScreen
import com.ankiwear.wear.screens.ErrorScreen
import com.ankiwear.wear.screens.ReviewScreen
import com.ankiwear.wear.theme.AnkiWearTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WearApp(dataLayerClient: DataLayerClient, prefs: ReviewPrefs? = null, demo: Boolean = false) {
    if (demo) {
        DemoWearApp(prefs)
    } else {
        LiveWearApp(dataLayerClient, prefs)
    }
}

/** The whole app on built-in sample cards, no phone needed. */
@Composable
private fun DemoWearApp(prefs: ReviewPrefs?) {
    AnkiWearTheme {
        val navController = rememberSwipeDismissableNavController()

        var currentCardIndex by remember { mutableIntStateOf(0) }
        var demoCards by remember { mutableStateOf(emptyList<CardData>()) }
        var focusMode by remember { mutableStateOf(prefs?.focusMode ?: true) }

        SwipeDismissableNavHost(
            navController = navController,
            startDestination = "decks"
        ) {
            composable("decks") {
                DeckListScreen(
                    decks = DemoContent.decks,
                    isLoading = false,
                    onDeckSelected = { deck ->
                        currentCardIndex = 0
                        demoCards = DemoContent.cards(deck.id)
                        navController.navigate("review")
                    }
                )
            }

            composable("review") {
                val currentCard = demoCards.getOrNull(currentCardIndex)
                val remaining = (demoCards.size - currentCardIndex).coerceAtLeast(0)

                ReviewScreen(
                    card = currentCard,
                    newRemaining = 0,
                    learnRemaining = 0,
                    reviewRemaining = remaining,
                    currentCardType = CardType.REVIEW,
                    revisionKey = currentCardIndex,
                    focusMode = focusMode,
                    onFocusModeChange = {
                        focusMode = it
                        prefs?.focusMode = it
                    },
                    onAnswer = { _, _, _, _ -> currentCardIndex++ },
                    onFinished = { navController.popBackStack() }
                )
            }
        }
    }
}

@Composable
private fun LiveWearApp(dataLayerClient: DataLayerClient, prefs: ReviewPrefs?) {
    AnkiWearTheme {
        val navController = rememberSwipeDismissableNavController()
        val scope = rememberCoroutineScope()

        val decks by dataLayerClient.decks.collectAsState()
        val cards by dataLayerClient.cards.collectAsState()
        val cardsResponseCount by dataLayerClient.cardsResponseCount.collectAsState()
        val authoritativeRemaining by dataLayerClient.cardsRemaining.collectAsState()
        val authoritativeNew by dataLayerClient.newRemaining.collectAsState()
        val authoritativeLearn by dataLayerClient.learnRemaining.collectAsState()
        val authoritativeReview by dataLayerClient.reviewRemaining.collectAsState()
        val errorMessage by dataLayerClient.errorMessage.collectAsState()
        val isConnected by dataLayerClient.isPhoneConnected.collectAsState()
        val decksLastUpdated by dataLayerClient.decksLastUpdated.collectAsState()

        var isLoadingDecks by remember { mutableStateOf(true) }
        var timedOut by remember { mutableStateOf(false) }
        var currentCardIndex by remember { mutableIntStateOf(0) }
        var selectedDeck by remember { mutableStateOf<DeckInfo?>(null) }
        var isFetchingCards by remember { mutableStateOf(false) }
        var focusMode by remember { mutableStateOf(prefs?.focusMode ?: true) }
        // Optimistic counter — decremented immediately on a non-Again tap so the user
        // sees instant feedback. The phone's authoritative count overwrites this
        // whenever a cards response arrives (LaunchedEffect below), so it self-heals
        // if the optimistic value drifted.
        var cardsRemaining by remember { mutableIntStateOf(0) }
        var newRemaining by remember { mutableIntStateOf(0) }
        var learnRemaining by remember { mutableIntStateOf(0) }
        var reviewRemaining by remember { mutableIntStateOf(0) }

        // Initial load: aggressive at first (1s polling for the first ~10s to handle
        // cold-start), then slower. After the visible timeout, KEEP retrying silently
        // in the background — most of the time the service comes online a few seconds
        // later and the deck list appears without the user ever touching retry.
        LaunchedEffect(Unit) {
            // Show any locally-cached decks instantly (survives cold start / paused
            // listener), then refresh from the phone in the background.
            dataLayerClient.loadCachedData()
            if (dataLayerClient.decks.value.isNotEmpty()) {
                isLoadingDecks = false
            }
            var attempts = 0
            while (dataLayerClient.decks.value.isEmpty()) {
                // A phone-reported config error (AnkiDroid missing, permission not granted,
                // no decks) will never clear by retrying — stop the poll loop and let the
                // error screen explain what to fix, instead of flickering loading↔error.
                if (dataLayerClient.phoneReportedError.value) {
                    isLoadingDecks = false
                    break
                }
                dataLayerClient.checkConnection()
                if (dataLayerClient.isPhoneConnected.value) {
                    dataLayerClient.requestDecks()
                }
                if (dataLayerClient.decks.value.isNotEmpty()) break

                // After ~12s of no response, surface the timeout screen — but keep
                // looping so we recover automatically once the phone wakes up.
                if (attempts == 12 && dataLayerClient.decks.value.isEmpty()) {
                    timedOut = true
                    isLoadingDecks = false
                }

                delay(if (attempts < 10) 1000 else 5000)
                attempts++
                if (attempts > 60) break // give up after ~5 minutes
            }
        }

        // When decks arrive, stop loading and dismiss the timeout screen — important
        // because the background poller may surface decks AFTER the user has been
        // shown the timeout screen, in which case we want to swap to the deck list
        // automatically rather than wait for them to tap retry.
        LaunchedEffect(decks) {
            if (decks.isNotEmpty()) {
                isLoadingDecks = false
                timedOut = false
            }
        }

        // Reset index AND clear fetching flag whenever a cards response arrives —
        // keyed on the response counter so identical-content responses (e.g. same card
        // re-queued after "Again") still trigger. Also reconcile the optimistic remaining
        // counters against the phone's authoritative numbers so we never drift.
        LaunchedEffect(cardsResponseCount) {
            if (cardsResponseCount > 0) {
                currentCardIndex = 0
                isFetchingCards = false
                cardsRemaining = authoritativeRemaining
                newRemaining = authoritativeNew
                learnRemaining = authoritativeLearn
                reviewRemaining = authoritativeReview
            }
        }

        // Refresh deck counts whenever the user navigates back to the deck list.
        // Without this, the deck overview would keep showing stale counts from app launch
        // even after the user answered cards on the watch.
        val currentBackStack by navController.currentBackStackEntryFlow
            .collectAsState(initial = null)
        val currentRoute = currentBackStack?.destination?.route
        LaunchedEffect(currentBackStack) {
            if (currentRoute == "decks" && cardsResponseCount > 0) {
                dataLayerClient.requestDecks()
            }
        }

        // On every Activity ON_RESUME (e.g. watch screen waking up), force a refresh
        // of whatever the user is currently looking at. This is the recovery path for
        // the "tap Good after wake-up shows the same card" bug — the in-memory `cards`
        // flow may be stale relative to the phone after a sleep cycle, so we re-sync
        // before any more taps go through.
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, currentRoute, selectedDeck) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    scope.launch {
                        dataLayerClient.checkConnection()
                        when (currentRoute) {
                            "decks" -> dataLayerClient.requestDecks()
                            "review" -> selectedDeck?.let {
                                dataLayerClient.requestCards(it.id)
                            }
                        }
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        SwipeDismissableNavHost(
            navController = navController,
            startDestination = "decks"
        ) {
            composable("decks") {
                val error = errorMessage
                when {
                    !isConnected && decks.isEmpty() && error == null && !isLoadingDecks -> {
                        DisconnectedScreen(
                            onRetry = {
                                timedOut = false
                                isLoadingDecks = true
                                scope.launch {
                                    var attempts = 0
                                    while (attempts < 10) {
                                        dataLayerClient.checkConnection()
                                        if (dataLayerClient.isPhoneConnected.value) {
                                            dataLayerClient.requestDecks()
                                        }
                                        if (dataLayerClient.decks.value.isNotEmpty()) break
                                        delay(3000)
                                        attempts++
                                    }
                                    isLoadingDecks = false
                                    if (dataLayerClient.decks.value.isEmpty()) timedOut = true
                                }
                            }
                        )
                    }
                    timedOut && decks.isEmpty() && isConnected -> {
                        ErrorScreen(
                            message = "Phone took too long to respond. This usually clears up after one retry — the phone-side service was probably cold-starting.",
                            onRetry = {
                                timedOut = false
                                isLoadingDecks = true
                                scope.launch {
                                    dataLayerClient.clearError()
                                    dataLayerClient.requestDecks()
                                    delay(10000)
                                    isLoadingDecks = false
                                    if (dataLayerClient.decks.value.isEmpty()) timedOut = true
                                }
                            }
                        )
                    }
                    error != null -> {
                        ErrorScreen(
                            message = error,
                            onRetry = {
                                scope.launch {
                                    dataLayerClient.clearError()
                                    dataLayerClient.checkConnection()
                                    dataLayerClient.requestDecks()
                                }
                            }
                        )
                    }
                    else -> {
                        DeckListScreen(
                            decks = decks,
                            isLoading = isLoadingDecks,
                            onDeckSelected = { deck ->
                                selectedDeck = deck
                                cardsRemaining = deck.totalDue
                                newRemaining = deck.newCount
                                learnRemaining = deck.learnCount
                                reviewRemaining = deck.reviewCount
                                isFetchingCards = true
                                scope.launch {
                                    dataLayerClient.requestCards(deck.id)
                                }
                                navController.navigate("review")
                            },
                            onRefresh = {
                                scope.launch {
                                    dataLayerClient.checkConnection()
                                    dataLayerClient.requestDecks()
                                }
                            },
                            lastUpdatedMillis = decksLastUpdated
                        )
                    }
                }
            }

            composable("review") {
                val currentCard = cards.getOrNull(currentCardIndex)

                // Revision key changes when the card identity OR the response counter
                // changes. This guarantees the question view and debounce state fully
                // reset even when "Again" returns the same note back to us. Built as a
                // string so distinct states can't collide the way an XOR of ints can
                // (a collision would leave the ease buttons permanently disabled).
                val revKey = remember(currentCardIndex, cardsResponseCount, currentCard?.noteId, currentCard?.cardOrd) {
                    "$cardsResponseCount:$currentCardIndex:${currentCard?.noteId}:${currentCard?.cardOrd}"
                }

                // Infer the current card's queue type for the counter underline only.
                // AnkiDroid serves cards in learning → new → review order, so we use the
                // remaining counts as the signal. This drives the underline; it is NOT
                // used to optimistically decrement a bucket — that could hit the wrong
                // bucket on mixed queues. Per-bucket counts come only from the phone's
                // authoritative response, which arrives within the fetch latency.
                val currentCardType = when {
                    currentCard == null -> CardType.NEW
                    learnRemaining > 0 -> CardType.LEARNING
                    newRemaining > 0 -> CardType.NEW
                    reviewRemaining > 0 -> CardType.REVIEW
                    else -> CardType.NEW
                }

                // Watchdog for the deck-open / list-exhausted fetch. Without this a lost
                // cards response leaves the review screen spinning forever. If nothing
                // arrives within ~8s, surface a retry instead of an infinite spinner.
                var reviewFetchTimedOut by remember { mutableStateOf(false) }
                var reviewRetryTick by remember { mutableIntStateOf(0) }
                LaunchedEffect(isFetchingCards, cardsResponseCount, reviewRetryTick) {
                    if (isFetchingCards) {
                        reviewFetchTimedOut = false
                        delay(8000)
                        if (isFetchingCards && dataLayerClient.cards.value.getOrNull(currentCardIndex) == null) {
                            reviewFetchTimedOut = true
                            isFetchingCards = false
                        }
                    }
                }

                if (reviewFetchTimedOut && currentCard == null) {
                    ErrorScreen(
                        message = "Couldn't reach your phone to load the next card. Tap retry — this usually clears once the phone-side service wakes up.",
                        onRetry = {
                            reviewFetchTimedOut = false
                            isFetchingCards = true
                            reviewRetryTick++
                            selectedDeck?.let { deck ->
                                scope.launch {
                                    dataLayerClient.checkConnection()
                                    dataLayerClient.requestCards(deck.id)
                                }
                            }
                        }
                    )
                    return@composable
                }

                ReviewScreen(
                    card = currentCard,
                    newRemaining = newRemaining,
                    learnRemaining = learnRemaining,
                    reviewRemaining = reviewRemaining,
                    currentCardType = currentCardType,
                    revisionKey = revKey,
                    isFetching = isFetchingCards,
                    focusMode = focusMode,
                    onFocusModeChange = {
                        focusMode = it
                        prefs?.focusMode = it
                    },
                    onAnswer = { noteId, cardOrd, ease, timeTakenMs ->
                        // The answer is written as a guaranteed-delivery DataItem; the phone
                        // applies it and sends back the next scheduled cards. Even if the
                        // link is down right now, the grade is queued and applies later.
                        selectedDeck?.let { deck ->
                            val expectedTick = cardsResponseCount
                            scope.launch {
                                dataLayerClient.sendAnswer(noteId, cardOrd, ease, timeTakenMs, deck.id)
                                // If the phone's response doesn't bump the counter within
                                // ~4s (link went stale after sleep), re-request cards so the
                                // user doesn't see the just-answered card again on next tap.
                                delay(4000)
                                if (dataLayerClient.cardsResponseCount.value == expectedTick) {
                                    dataLayerClient.requestCards(deck.id)
                                }
                            }
                        }
                        // Optimistically decrement the total for non-Again answers so the
                        // session feels responsive. Per-bucket counts are intentionally NOT
                        // touched here (see currentCardType note above) — they self-heal
                        // from the phone's authoritative response.
                        if (ease > 1) {
                            cardsRemaining = (cardsRemaining - 1).coerceAtLeast(0)
                        }
                        val nextIndex = currentCardIndex + 1
                        if (nextIndex >= cards.size) {
                            isFetchingCards = true
                        }
                        currentCardIndex = nextIndex
                    },
                    onFinished = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
