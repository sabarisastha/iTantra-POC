package com.itantra.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.itantra.model.ModelManager
import com.itantra.session.SessionController
import com.itantra.transport.TransportChannel
import com.itantra.ui.home.HomeScreen
import com.itantra.ui.session.SessionScreen
import kotlinx.coroutines.flow.StateFlow

private const val ROUTE_HOME    = "home"
private const val ROUTE_SESSION = "session"

@Composable
fun MainNavGraph(
    transport: TransportChannel,
    sessionController: SessionController,
    modelManager: ModelManager,
    permissionsGranted: StateFlow<Boolean>
) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = ROUTE_HOME) {
        composable(ROUTE_HOME) {
            HomeScreen(
                transport           = transport,
                sessionController   = sessionController,
                modelManager        = modelManager,
                onNavigateToSession = { navController.navigate(ROUTE_SESSION) },
                permissionsGranted  = permissionsGranted
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
