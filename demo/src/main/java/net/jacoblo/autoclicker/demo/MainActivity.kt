package net.jacoblo.autoclicker.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A fixture for driving AutoClicker against something that never changes.
 *
 * Every screen is deliberately plain and deliberately fixed. The point is not
 * to look like any particular app; it is to hold still, so a saved area matches
 * on the tenth run as well as the first and a failed step means the automation
 * broke rather than the target moved.
 *
 * Three properties are load-bearing for the tests that use it:
 *
 * - Each screen carries a heading whose wording appears nowhere else, so it can
 *   be told apart by image alone.
 * - The gap from a heading to the control beneath it is [GAP_BELOW_HEADING] on
 *   every screen, which is what makes a coordinate measured relative to the
 *   heading reusable.
 * - Colours are pinned to the light scheme whatever the system is set to, and
 *   the flow restarts on launch, so a run cannot inherit anything from the one
 *   before it.
 */

// Wrong codes are rejected so a script has to actually work through a list of
// candidates rather than getting away with the first.
const val ACCEPTED_CODE = "246810"

private val GAP_BELOW_HEADING = 24.dp
private const val CODE_LENGTH = 6

private enum class Step {
	START, EMAIL, CODE, DATE, NAME, USERNAME, PASSWORD,
	SUGGESTIONS, PHOTO, INTERESTS, FOLLOW, DONE
}

class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContent {
			// Pinned rather than dynamic: a fixture that changes with the
			// system theme cannot be matched against a saved image.
			MaterialTheme(colorScheme = lightColorScheme()) {
				Surface(color = Color.White) { DemoFlow() }
			}
		}
	}
}

@Composable
private fun DemoFlow() {
	var step by remember { mutableStateOf(Step.START) }

	// Kept so a test can read back what the automation actually entered.
	var email by remember { mutableStateOf("") }
	var name by remember { mutableStateOf("") }
	var username by remember { mutableStateOf("preset-name") }
	var password by remember { mutableStateOf("") }
	var interests by remember { mutableStateOf(setOf<String>()) }

	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(horizontal = 24.dp)
			.padding(top = 72.dp)
	) {
		when (step) {
			Step.START -> StartScreen { step = Step.EMAIL }

			Step.EMAIL -> TextEntryScreen(
				heading = "Enter your email",
				value = email,
				onValueChange = { email = it },
				keyboardType = KeyboardType.Email,
				onContinue = { step = Step.CODE }
			)

			Step.CODE -> CodeScreen { step = Step.DATE }

			Step.DATE -> DateScreen { step = Step.NAME }

			Step.NAME -> TextEntryScreen(
				heading = "What is your name",
				value = name,
				onValueChange = { name = it },
				onContinue = { step = Step.USERNAME }
			)

			// Starts populated, so clearing it is part of the job.
			Step.USERNAME -> TextEntryScreen(
				heading = "Choose a username",
				value = username,
				onValueChange = { username = it },
				onContinue = { step = Step.PASSWORD }
			)

			Step.PASSWORD -> PasswordScreen(
				value = password,
				onValueChange = { password = it },
				onContinue = { step = Step.SUGGESTIONS }
			)

			Step.SUGGESTIONS -> DismissScreen(
				heading = "People you may know",
				dismissLabel = "Not now",
				onDismiss = { step = Step.PHOTO }
			)

			Step.PHOTO -> DismissScreen(
				heading = "Add a photo",
				dismissLabel = "Skip",
				onDismiss = { step = Step.INTERESTS }
			)

			Step.INTERESTS -> InterestsScreen(
				selected = interests,
				onToggle = { tag ->
					interests = if (tag in interests) interests - tag else interests + tag
				},
				onContinue = { step = Step.FOLLOW }
			)

			Step.FOLLOW -> FollowScreen { step = Step.DONE }

			Step.DONE -> DoneScreen(
				email = email,
				name = name,
				username = username,
				password = password,
				interests = interests,
				onRestart = {
					email = ""; name = ""; username = "preset-name"
					password = ""; interests = emptySet()
					step = Step.START
				}
			)
		}
	}
}

// ---------------------------------------------------------------------------
// Screens
// ---------------------------------------------------------------------------

/** Three icons that look nothing like each other, so only one can match. */
@Composable
private fun StartScreen(onEmail: () -> Unit) {
	Heading("Choose how to sign in")
	Spacer(modifier = Modifier.height(GAP_BELOW_HEADING))
	Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
		IconTile(Icons.Default.Email, "email", Color(0xFF1E88E5), onEmail)
		IconTile(Icons.Default.Phone, "phone", Color(0xFF43A047)) {}
		IconTile(Icons.Default.Person, "person", Color(0xFFF4511E)) {}
	}
}

@Composable
private fun IconTile(
	icon: androidx.compose.ui.graphics.vector.ImageVector,
	label: String,
	tint: Color,
	onClick: () -> Unit
) {
	Column(horizontalAlignment = Alignment.CenterHorizontally) {
		Box(
			modifier = Modifier
				.size(72.dp)
				.background(tint, CircleShape)
				.clickable { onClick() },
			contentAlignment = Alignment.Center
		) {
			Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(40.dp))
		}
		Spacer(modifier = Modifier.height(8.dp))
		Text(label, fontSize = 14.sp)
	}
}

@Composable
private fun TextEntryScreen(
	heading: String,
	value: String,
	onValueChange: (String) -> Unit,
	onContinue: () -> Unit,
	keyboardType: KeyboardType = KeyboardType.Text
) {
	Heading(heading)
	Spacer(modifier = Modifier.height(GAP_BELOW_HEADING))
	OutlinedTextField(
		value = value,
		onValueChange = onValueChange,
		singleLine = true,
		keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
		modifier = Modifier.fillMaxWidth()
	)
	Spacer(modifier = Modifier.height(24.dp))
	ContinueButton(onContinue)
}

/**
 * Six boxes fed by one field underneath them, the way a real code entry works:
 * a tap anywhere on the row starts typing at the first empty box.
 */
@Composable
private fun CodeScreen(onAccepted: () -> Unit) {
	var code by remember { mutableStateOf("") }
	var rejected by remember { mutableStateOf(false) }
	val focus = remember { FocusRequester() }

	Heading("Enter the code")
	Spacer(modifier = Modifier.height(GAP_BELOW_HEADING))

	BasicTextField(
		value = code,
		onValueChange = {
			code = it.filter(Char::isDigit).take(CODE_LENGTH)
			rejected = false
		},
		keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
		modifier = Modifier.focusRequester(focus),
		decorationBox = {
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				repeat(CODE_LENGTH) { index ->
					Box(
						modifier = Modifier
							.size(48.dp, 56.dp)
							.border(2.dp, Color(0xFF9E9E9E), RoundedCornerShape(6.dp))
							.clickable { focus.requestFocus() },
						contentAlignment = Alignment.Center
					) {
						Text(
							text = code.getOrNull(index)?.toString() ?: "",
							fontSize = 24.sp,
							fontWeight = FontWeight.Medium
						)
					}
				}
			}
		}
	)

	if (rejected) {
		Spacer(modifier = Modifier.height(12.dp))
		Text("That code is not right", color = Color(0xFFD32F2F), fontSize = 16.sp)
	}

	Spacer(modifier = Modifier.height(24.dp))
	ContinueButton {
		if (code == ACCEPTED_CODE) onAccepted() else {
			rejected = true
			code = ""
		}
	}
}

/** Three lists to flick through; the top visible row of each is the choice. */
@Composable
private fun DateScreen(onContinue: () -> Unit) {
	val days = remember { (1..28).map { it.toString() } }
	val months = remember {
		listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
	}
	val years = remember { (1970..2005).map { it.toString() } }

	val dayState = rememberLazyListState()
	val monthState = rememberLazyListState()
	val yearState = rememberLazyListState()

	Heading("Pick a date")
	Spacer(modifier = Modifier.height(GAP_BELOW_HEADING))

	Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
		WheelColumn(days, dayState, Modifier.weight(1f))
		WheelColumn(months, monthState, Modifier.weight(1f))
		WheelColumn(years, yearState, Modifier.weight(1f))
	}

	Spacer(modifier = Modifier.height(16.dp))
	Text(
		"Chosen: ${days[dayState.firstVisibleItemIndex]} " +
			"${months[monthState.firstVisibleItemIndex]} " +
			years[yearState.firstVisibleItemIndex],
		fontSize = 16.sp
	)
	Spacer(modifier = Modifier.height(16.dp))
	ContinueButton(onContinue)
}

@Composable
private fun WheelColumn(
	values: List<String>,
	state: androidx.compose.foundation.lazy.LazyListState,
	modifier: Modifier = Modifier
) {
	LazyColumn(
		state = state,
		modifier = modifier
			.height(180.dp)
			.border(1.dp, Color(0xFFBDBDBD), RoundedCornerShape(8.dp))
	) {
		items(values) { value ->
			Text(
				text = value,
				fontSize = 20.sp,
				textAlign = TextAlign.Center,
				modifier = Modifier
					.fillMaxWidth()
					.padding(vertical = 12.dp)
			)
		}
	}
}

@Composable
private fun PasswordScreen(value: String, onValueChange: (String) -> Unit, onContinue: () -> Unit) {
	var visible by remember { mutableStateOf(false) }

	Heading("Set a password")
	Spacer(modifier = Modifier.height(GAP_BELOW_HEADING))
	OutlinedTextField(
		value = value,
		onValueChange = onValueChange,
		singleLine = true,
		visualTransformation = if (visible) {
			androidx.compose.ui.text.input.VisualTransformation.None
		} else {
			PasswordVisualTransformation()
		},
		trailingIcon = {
			IconButton(onClick = { visible = !visible }) {
				Icon(
					imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
					contentDescription = if (visible) "hide password" else "show password"
				)
			}
		},
		modifier = Modifier.fillMaxWidth()
	)
	Spacer(modifier = Modifier.height(8.dp))
	Text(if (visible) "Password is showing" else "Password is hidden", fontSize = 14.sp)
	Spacer(modifier = Modifier.height(24.dp))
	ContinueButton(onContinue)
}

@Composable
private fun DismissScreen(heading: String, dismissLabel: String, onDismiss: () -> Unit) {
	Heading(heading)
	Spacer(modifier = Modifier.height(GAP_BELOW_HEADING))
	Text("Nothing here matters, the button is the point.", fontSize = 16.sp)
	Spacer(modifier = Modifier.height(24.dp))
	OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
		Text(dismissLabel, fontSize = 18.sp)
	}
}

@Composable
private fun InterestsScreen(selected: Set<String>, onToggle: (String) -> Unit, onContinue: () -> Unit) {
	val tags = listOf("Tech", "Finance", "Memes", "Sport", "Music", "Travel")

	Heading("Pick interests")
	Spacer(modifier = Modifier.height(GAP_BELOW_HEADING))
	// A fixed two-column grid, so every chip keeps its place run to run.
	tags.chunked(2).forEach { row ->
		Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
			row.forEach { tag ->
				FilterChip(
					selected = tag in selected,
					onClick = { onToggle(tag) },
					label = { Text(tag, fontSize = 16.sp) },
					modifier = Modifier
						.weight(1f)
						.height(56.dp)
				)
			}
		}
		Spacer(modifier = Modifier.height(12.dp))
	}
	Spacer(modifier = Modifier.height(12.dp))
	Text("Picked ${selected.size}", fontSize = 16.sp)
	Spacer(modifier = Modifier.height(12.dp))
	ContinueButton(onContinue)
}

@Composable
private fun FollowScreen(onContinue: () -> Unit) {
	var followed by remember { mutableStateOf(setOf<String>()) }
	val accounts = listOf("alpha", "beta", "gamma")

	Heading("Suggested accounts")
	Spacer(modifier = Modifier.height(GAP_BELOW_HEADING))
	accounts.forEach { account ->
		Row(
			modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			Text(account, fontSize = 18.sp, modifier = Modifier.weight(1f))
			Button(onClick = {
				followed = if (account in followed) followed - account else followed + account
			}) {
				Text(if (account in followed) "Following" else "Follow")
			}
		}
	}
	Spacer(modifier = Modifier.height(24.dp))
	ContinueButton(onContinue)
}

/** Everything that was entered, so a test can read the outcome off the screen. */
@Composable
private fun DoneScreen(
	email: String,
	name: String,
	username: String,
	password: String,
	interests: Set<String>,
	onRestart: () -> Unit
) {
	Heading("All done")
	Spacer(modifier = Modifier.height(GAP_BELOW_HEADING))
	listOf(
		"email" to email,
		"name" to name,
		"username" to username,
		"password" to password,
		"interests" to interests.sorted().joinToString(",")
	).forEach { (label, value) ->
		Text(
			text = "$label = ${value.ifBlank { "(empty)" }}",
			fontSize = 16.sp,
			fontFamily = FontFamily.Monospace,
			modifier = Modifier.padding(vertical = 2.dp)
		)
	}
	Spacer(modifier = Modifier.height(24.dp))
	OutlinedButton(onClick = onRestart) { Text("Start over") }
}

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

/**
 * The anchor every relative coordinate is measured from, so its size is fixed
 * rather than left to wrap: a heading that reflowed onto two lines on a narrow
 * screen would move everything below it.
 */
@Composable
private fun Heading(text: String) {
	Text(
		text = text,
		fontSize = 26.sp,
		fontWeight = FontWeight.Bold,
		maxLines = 1,
		modifier = Modifier.fillMaxWidth().height(40.dp)
	)
}

/** Directly under the content rather than at the screen bottom, so the on-screen keyboard never covers it. */
@Composable
private fun ContinueButton(onClick: () -> Unit) {
	Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp)) {
		Text("Continue", fontSize = 18.sp)
	}
}
