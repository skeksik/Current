package com.skeksik.current.feature.settings

import com.skeksik.current.core.network.ServiceErrorKind
import com.skeksik.current.core.network.ServiceException
import com.skeksik.current.data.jellyfin.SignInException
import com.skeksik.current.data.jellyfin.SignInFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SignInViewModelTest {
    private fun TestScope.viewModel(accounts: FakeAccounts) = SignInViewModel(accounts, home, scope = backgroundScope)

    private fun SignInViewModel.form() = assertIs<SignInUiState.Form>(state.value)

    @Test
    fun quickConnectIsOfferedWhenTheServerHasItOn() = runTest {
        val viewModel = viewModel(FakeAccounts())
        runCurrent()
        assertTrue(viewModel.form().quickConnect)
    }

    @Test
    fun aPasswordSignInEndsSignedIn() = runTest {
        val accounts = FakeAccounts()
        val viewModel = viewModel(accounts)
        viewModel.onUsername(" alex ")
        viewModel.onPassword("pw")
        viewModel.signIn()
        runCurrent()
        assertEquals(SignInUiState.SignedIn("Alex"), viewModel.state.value)
        assertEquals(listOf("alex"), accounts.signIns)
    }

    @Test
    fun failuresBecomeProblemsOnTheForm() = runTest {
        val cases = mapOf(
            ServiceException(home, ServiceErrorKind.Unauthorized, 401) to SignInProblem.WrongCredentials,
            ServiceException(home, ServiceErrorKind.Unreachable) to SignInProblem.Unreachable,
            ServiceException(home, ServiceErrorKind.ServerError, 500) to SignInProblem.Refused,
            SignInException(SignInFailure.DifferentServer) to SignInProblem.DifferentServer,
        )
        cases.forEach { (error, problem) ->
            val viewModel = viewModel(FakeAccounts().apply { signInResult = { throw error } })
            viewModel.onUsername("alex")
            viewModel.signIn()
            runCurrent()
            val form = viewModel.form()
            assertEquals(problem, form.problem)
            assertEquals("alex", form.username)
            assertFalse(form.working)
        }
    }

    @Test
    fun aBlankUsernameDoesNothing() = runTest {
        val accounts = FakeAccounts()
        val viewModel = viewModel(accounts)
        viewModel.signIn()
        runCurrent()
        assertFalse(viewModel.form().working)
        assertTrue(accounts.signIns.isEmpty())
    }

    @Test
    fun quickConnectShowsTheCodeUntilApproved() = runTest {
        val accounts = FakeAccounts()
        val viewModel = viewModel(accounts)
        viewModel.startQuickConnect()
        runCurrent()
        assertEquals(SignInUiState.QuickConnect("123456"), viewModel.state.value)
        accounts.approval.complete(Unit)
        runCurrent()
        assertEquals(SignInUiState.SignedIn("Alex"), viewModel.state.value)
    }

    @Test
    fun anExpiredCodeGoesBackToTheForm() = runTest {
        val accounts = FakeAccounts().apply { finishFailure = SignInFailure.QuickConnectExpired }
        val viewModel = viewModel(accounts)
        viewModel.startQuickConnect()
        runCurrent()
        accounts.approval.complete(Unit)
        runCurrent()
        assertEquals(SignInProblem.QuickConnectExpired, viewModel.form().problem)
        assertFalse(viewModel.form().working)
    }

    @Test
    fun startingQuickConnectHoldsTheFormUntilTheCodeArrives() = runTest {
        val accounts = FakeAccounts().apply { codeReady = CompletableDeferred() }
        val viewModel = viewModel(accounts)
        viewModel.onUsername("alex")
        viewModel.startQuickConnect()
        runCurrent()
        assertTrue(viewModel.form().working)

        // Sign in (or Enter in the password field) before the code shows: ignored, so only one job runs.
        viewModel.signIn()
        viewModel.startQuickConnect()
        runCurrent()
        assertTrue(accounts.signIns.isEmpty())

        accounts.codeReady.complete(Unit)
        runCurrent()
        assertEquals(SignInUiState.QuickConnect("123456"), viewModel.state.value)
    }

    @Test
    fun aFailedQuickConnectStartFreesTheForm() = runTest {
        val accounts = FakeAccounts().apply { codeReady = CompletableDeferred() }
        val viewModel = viewModel(accounts)
        viewModel.startQuickConnect()
        runCurrent()
        accounts.codeReady.completeExceptionally(SignInException(SignInFailure.QuickConnectDisabled))
        runCurrent()
        assertEquals(SignInProblem.QuickConnectDisabled, viewModel.form().problem)
        assertFalse(viewModel.form().working)
    }

    @Test
    fun cancellingQuickConnectStopsTheWaitAndReturnsToTheForm() = runTest {
        val accounts = FakeAccounts()
        val viewModel = viewModel(accounts)
        viewModel.startQuickConnect()
        runCurrent()
        viewModel.cancelQuickConnect()
        runCurrent()
        assertEquals(SignInUiState.Form(quickConnect = true), viewModel.state.value)
        assertTrue(accounts.finishCancelled)

        // An approval that comes later changes nothing.
        accounts.approval.complete(Unit)
        runCurrent()
        assertEquals(SignInUiState.Form(quickConnect = true), viewModel.state.value)
    }

    @Test
    fun aPasswordSignInAfterACancelledQuickConnectEndsSignedIn() = runTest {
        val accounts = FakeAccounts()
        val viewModel = viewModel(accounts)
        viewModel.startQuickConnect()
        runCurrent()
        viewModel.cancelQuickConnect()
        viewModel.onUsername("alex")
        viewModel.signIn()
        runCurrent()
        accounts.approval.complete(Unit)
        runCurrent()
        assertEquals(SignInUiState.SignedIn("Alex"), viewModel.state.value)
        assertEquals(listOf("alex"), accounts.signIns)
    }
}
