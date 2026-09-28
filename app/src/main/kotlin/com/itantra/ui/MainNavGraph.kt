package com.itantra.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.itantra.model.ModelManager
import com.itantra.session.SessionController
import com.itantra.transport.TransportManager
import com.itantra.ui.home.HomeScreen
import com.itantra.ui.session.SessionScreen
import com.itantra.ui.setup.SplashAnimationScreen
import com.itantra.ui.setup.LanguageSelectionScreen
import com.itantra.ui.setup.ModelReadinessScreen
import kotlinx.coroutines.flow.StateFlow

private const val ROUTE_SPLASH   = "splash"
private const val ROUTE_LANGUAGE = "language"
private const val ROUTE_MODELS   = "models"
private const val ROUTE_HOME     = "home"
private const val ROUTE_SESSION  = "session"

@Composable
fun MainNavGraph(
    transport: TransportManager,
    sessionController: SessionController,
    modelManager: ModelManager,
    permissionsGranted: StateFlow<Boolean>,
    historyDao: com.itantra.db.HistoryDao? = null,
) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = ROUTE_SPLASH) {
        composable(ROUTE_SPLASH) {
            SplashAnimationScreen(onAnimationFinished = {
                navController.navigate(ROUTE_LANGUAGE) {
                    popUpTo(ROUTE_SPLASH) { inclusive = true }
                }
            })
        }
        composable(ROUTE_LANGUAGE) {
            LanguageSelectionScreen(
                sessionController = sessionController,
                modelManager = modelManager,
                onLanguageSelected = {
                    navController.navigate(ROUTE_MODELS) {
                        popUpTo(ROUTE_LANGUAGE) { inclusive = true }
                    }
                }
            )
        }
        composable(ROUTE_MODELS) {
            ModelReadinessScreen(
                sessionController = sessionController,
                modelManager = modelManager,
                onReady = {
                    navController.navigate(ROUTE_HOME) {
                        popUpTo(ROUTE_MODELS) { inclusive = true }
                    }
                }
            )
        }
        composable(ROUTE_HOME) {
            HomeScreen(
                transport           = transport,
                sessionController   = sessionController,
                modelManager        = modelManager,
                onNavigateToSession = { navController.navigate(ROUTE_SESSION) },
                permissionsGranted  = permissionsGranted,
                historyDao          = historyDao,
            )
        }
        composable(ROUTE_SESSION) {
            SessionScreen(
                sessionController = sessionController,
                onNavigateBack    = { navController.popBackStack() },
            )
        }
    }
}
