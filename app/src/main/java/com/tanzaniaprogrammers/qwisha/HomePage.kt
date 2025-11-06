package com.tanzaniaprogrammers.qwisha

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.constraintlayout.compose.ConstraintLayout

@Composable
fun HomePage() {
    ConstraintLayout (modifier = Modifier.fillMaxSize()) {
        val (welcomeText) = createRefs()
        Text(
            text = "Qwisha",
            modifier = Modifier.constrainAs(welcomeText){
                top.linkTo(parent.top)
                start.linkTo(parent.start)
                end.linkTo(parent.end)
                bottom.linkTo(parent.bottom)
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun HomePagePreview() {
    HomePage()
}

