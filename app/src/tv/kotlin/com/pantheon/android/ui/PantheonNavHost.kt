package com.pantheon.android.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pantheon.android.api.ApiClient
import com.pantheon.android.auth.AuthViewModel
import com.pantheon.android.auth.ProfileSelectScreen
import com.pantheon.android.auth.TokenStore
import com.pantheon.android.detail.DetailScreen
import com.pantheon.android.guide.GuideScreen
import com.pantheon.android.home.HomeScreen
import com.pantheon.android.library.LibraryScreen
import com.pantheon.android.player.PlayerScreen

// TV counterpart of the mobile flavor's PantheonNavHost.kt — same routes,
// same shared ConnectScreen/LoginScreen (plain compose.material3 widgets;
// D-pad-focusable via Compose's generic focus system, just not yet styled
// with tv-material — a known follow-up, not an oversight, since this pass's
// scope is the Home screen specifically), TV-specific HomeScreen for the
// actual browse experience.
private object Routes {
    const val CONNECT = "connect"
    const val LOGIN = "login"
    const val PROFILES = "profiles"
    const val HOME = "home"
    const val LIBRARY = "library"
    const val GUIDE = "guide"
    const val DETAIL = "detail/{contentType}/{id}"
    const val PLAYER = "player/{kind}/{id}?positionMs={positionMs}"
    fun detail(contentType: String, id: String) = "detail/$contentType/$id"
    fun player(kind: String, id: String, positionMs: Long) = "player/$kind/$id?positionMs=$positionMs"
}

@Composable
fun PantheonNavHost(tokenStore: TokenStore, apiClient: ApiClient) {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = viewModel(factory = AuthViewModel.factory(tokenStore, apiClient))
    // See the mobile flavor's own comment — a stored session lands on the
    // profile picker, not straight on Home, mirroring AuthContext.tsx's
    // deliberately in-memory-only profileChosen.
    val startDestination = if (authViewModel.hasStoredSession) Routes.PROFILES else Routes.CONNECT

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.CONNECT) {
            ConnectScreen(authViewModel) { navController.navigate(Routes.LOGIN) }
        }
        composable(Routes.LOGIN) {
            LoginScreen(authViewModel) {
                navController.navigate(Routes.PROFILES) { popUpTo(Routes.CONNECT) { inclusive = true } }
            }
        }
        composable(Routes.PROFILES) {
            ProfileSelectScreen(
                viewModel = authViewModel,
                onProfileChosen = { navController.navigate(Routes.HOME) { popUpTo(Routes.PROFILES) { inclusive = true } } },
                onSignOutCompletely = {
                    authViewModel.logout()
                    navController.navigate(Routes.CONNECT) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                apiClient = apiClient,
                onOpenDetail = { contentType, id -> navController.navigate(Routes.detail(contentType, id)) },
                onPlay = { kind, id, positionMs -> navController.navigate(Routes.player(kind, id, positionMs)) },
                onNavigateLibrary = { navController.navigate(Routes.LIBRARY) },
                onNavigateGuide = { navController.navigate(Routes.GUIDE) },
                onSwitchProfile = { navController.navigate(Routes.PROFILES) },
            )
        }
        composable(Routes.GUIDE) {
            GuideScreen(
                apiClient = apiClient,
                onWatchChannel = { channelId -> navController.navigate(Routes.player("channel", channelId, 0)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(
                apiClient = apiClient,
                onOpenDetail = { contentType, id -> navController.navigate(Routes.detail(contentType, id)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.DETAIL,
            arguments = listOf(navArgument("contentType") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType }),
        ) { backStackEntry ->
            val contentType = backStackEntry.arguments?.getString("contentType") ?: ""
            val id = backStackEntry.arguments?.getString("id") ?: ""
            DetailScreen(
                apiClient = apiClient,
                contentType = contentType,
                id = id,
                onPlay = { kind, playId, positionMs -> navController.navigate(Routes.player(kind, playId, positionMs)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.PLAYER,
            arguments = listOf(
                navArgument("kind") { type = NavType.StringType },
                navArgument("id") { type = NavType.StringType },
                navArgument("positionMs") { type = NavType.LongType; defaultValue = 0L },
            ),
        ) { backStackEntry ->
            val kind = backStackEntry.arguments?.getString("kind") ?: ""
            val id = backStackEntry.arguments?.getString("id") ?: ""
            val positionMs = backStackEntry.arguments?.getLong("positionMs") ?: 0L
            PlayerScreen(
                apiClient = apiClient,
                kind = kind,
                contentId = id,
                initialPositionMs = positionMs,
                onBack = { navController.popBackStack() },
                // See the mobile flavor's own comment — replace-style
                // navigation so Back after an auto-advance leaves the
                // player entirely rather than stepping into the just-
                // finished episode.
                onAdvanceToNext = { nextId ->
                    navController.navigate(Routes.player("episode", nextId, 0)) {
                        popUpTo(Routes.PLAYER) { inclusive = true }
                    }
                },
            )
        }
    }
}
