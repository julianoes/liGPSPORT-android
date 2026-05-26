package de.syntaxfehler.ligpsport

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.syntaxfehler.ligpsport.ui.map.MapScreen
import de.syntaxfehler.ligpsport.ui.pairing.PairingScreen
import de.syntaxfehler.ligpsport.ui.settings.DeviceActivitiesScreen
import de.syntaxfehler.ligpsport.ui.settings.DeviceRoutesScreen
import de.syntaxfehler.ligpsport.ui.settings.SettingsScreen
import de.syntaxfehler.ligpsport.ui.theme.LigpsportTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LigpsportTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNav()
                }
            }
        }
    }
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "map") {
        composable("map") {
            MapScreen(
                // Pairing is reached via Settings, not from the map
                // directly — the gear icon owns device management.
                onOpenPairing = { nav.navigate("settings") },
                onOpenSettings = { nav.navigate("settings") },
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenPairing = { nav.navigate("pairing") },
                onOpenRoutes = { mac -> nav.navigate("settings/routes/$mac") },
                onOpenActivities = { mac -> nav.navigate("settings/activities/$mac") },
            )
        }
        composable(
            route = "settings/routes/{mac}",
            arguments = listOf(navArgument("mac") { type = NavType.StringType }),
        ) { entry ->
            DeviceRoutesScreen(
                onBack = { nav.popBackStack() },
                targetMac = entry.arguments?.getString("mac"),
            )
        }
        composable(
            route = "settings/activities/{mac}",
            arguments = listOf(navArgument("mac") { type = NavType.StringType }),
        ) { entry ->
            DeviceActivitiesScreen(
                onBack = { nav.popBackStack() },
                targetMac = entry.arguments?.getString("mac"),
            )
        }
        composable("pairing") {
            PairingScreen(
                onPaired = { nav.popBackStack() },
                onBack = { nav.popBackStack() },
            )
        }
    }
}
