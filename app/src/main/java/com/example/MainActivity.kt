package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.ui.ScreenReaderViewModel
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.JsonRecordsScreen
import com.example.ui.screens.McpServerScreen
import com.example.ui.theme.MyApplicationTheme

enum class MainDestination(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    HOME("Lecteur & Scroll", Icons.Filled.Visibility, Icons.Outlined.Visibility, "nav_home"),
    RECORDS("Fichiers JSON", Icons.Filled.DataObject, Icons.Outlined.DataObject, "nav_records"),
    MCP("Protocole MCP", Icons.Filled.Cable, Icons.Outlined.Cable, "nav_mcp")
}

class MainActivity : ComponentActivity() {
    private val viewModel: ScreenReaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainAppScreen(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun MainAppScreen(viewModel: ScreenReaderViewModel) {
    var currentDestinationIndex by rememberSaveable { mutableIntStateOf(0) }
    val destinations = MainDestination.values()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                modifier = Modifier
                    .navigationBarsPadding()
                    .testTag("main_bottom_nav")
            ) {
                destinations.forEachIndexed { index, destination ->
                    val isSelected = currentDestinationIndex == index
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { currentDestinationIndex = index },
                        icon = {
                            Icon(
                                imageVector = if (isSelected) destination.selectedIcon else destination.unselectedIcon,
                                contentDescription = destination.title
                            )
                        },
                        label = {
                            Text(
                                text = destination.title,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        modifier = Modifier.testTag(destination.testTag)
                    )
                }
            }
        }
    ) { innerPadding ->
        when (destinations[currentDestinationIndex]) {
            MainDestination.HOME -> HomeScreen(
                viewModel = viewModel,
                modifier = Modifier.padding(innerPadding)
            )
            MainDestination.RECORDS -> JsonRecordsScreen(
                viewModel = viewModel,
                modifier = Modifier.padding(innerPadding)
            )
            MainDestination.MCP -> McpServerScreen(
                viewModel = viewModel,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}
