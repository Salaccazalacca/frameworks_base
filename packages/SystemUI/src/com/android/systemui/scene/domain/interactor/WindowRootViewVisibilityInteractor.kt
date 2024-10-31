/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui.scene.domain.interactor

import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.scene.data.repository.WindowRootViewVisibilityRepository
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.statusbar.NotificationPresenter
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.init.NotificationsController
import com.android.systemui.statusbar.notification.shared.NotificationsLiveDataStoreRefactor
import com.android.systemui.statusbar.policy.HeadsUpManager
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Business logic about the visibility of various parts of the window root view. */
@SysUISingleton
class WindowRootViewVisibilityInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val windowRootViewVisibilityRepository: WindowRootViewVisibilityRepository,
    private val keyguardRepository: KeyguardRepository,
    private val headsUpManager: HeadsUpManager,
    private val powerInteractor: PowerInteractor,
    private val activeNotificationsInteractor: ActiveNotificationsInteractor,
    sceneContainerFlags: SceneContainerFlags,
    sceneInteractorProvider: Provider<SceneInteractor>,
) : CoreStartable {

    private var notificationPresenter: NotificationPresenter? = null
    private var notificationsController: NotificationsController? = null

    private val isNotifPresenterFullyCollapsed: Boolean
        get() = notificationPresenter?.isPresenterFullyCollapsed ?: true

    /**
     * True if lockscreen (including AOD) or the shade is visible and false otherwise. Notably,
     * false if the bouncer is visible.
     */
    val isLockscreenOrShadeVisible: StateFlow<Boolean> =
        if (!sceneContainerFlags.isEnabled()) {
            windowRootViewVisibilityRepository.isLockscreenOrShadeVisible
        } else {
            sceneInteractorProvider
                .get()
                .transitionState
                .map { state ->
                    when (state) {
                        is ObservableTransitionState.Idle ->
                            state.scene == Scenes.Shade || state.scene == Scenes.Lockscreen
                        is ObservableTransitionState.Transition ->
                            state.toScene == Scenes.Shade ||
                                state.toScene == Scenes.Lockscreen ||
                                state.fromScene == Scenes.Shade ||
                                state.fromScene == Scenes.Lockscreen
                    }
                }
                .distinctUntilChanged()
                .stateIn(scope, SharingStarted.Eagerly, false)
        }

    /**
     * True if lockscreen (including AOD) or the shade is visible **and** the user is currently
     * interacting with the device, false otherwise. Notably, false if the bouncer is visible and
     * false if the device is asleep.
     */
    val isLockscreenOrShadeVisibleAndInteractive: StateFlow<Boolean> =
        combine(
                isLockscreenOrShadeVisible,
                powerInteractor.isAwake,
            ) { isKeyguardAodOrShadeVisible, isAwake ->
                isKeyguardAodOrShadeVisible && isAwake
            }
            .stateIn(scope, SharingStarted.Eagerly, initialValue = false)

    /**
     * Sets classes that aren't easily injectable on this class.
     *
     * TODO(b/277762009): Inject these directly instead.
     */
    fun setUp(
        presenter: NotificationPresenter?,
        notificationsController: NotificationsController?,
    ) {
        this.notificationPresenter = presenter
        this.notificationsController = notificationsController
    }

    override fun start() {
        scope.launch {
            isLockscreenOrShadeVisibleAndInteractive.collect { interactive ->
                if (interactive) {
                    windowRootViewVisibilityRepository.onLockscreenOrShadeInteractive(
                        getShouldClearNotificationEffects(keyguardRepository.statusBarState.value),
                        getNotificationLoad(),
                    )
                } else {
                    windowRootViewVisibilityRepository.onLockscreenOrShadeNotInteractive()
                }
            }
        }
    }

    fun setIsLockscreenOrShadeVisible(visible: Boolean) {
        windowRootViewVisibilityRepository.setIsLockscreenOrShadeVisible(visible)
    }

    private fun getShouldClearNotificationEffects(statusBarState: StatusBarState): Boolean {
        return !isNotifPresenterFullyCollapsed &&
            (statusBarState == StatusBarState.SHADE ||
                statusBarState == StatusBarState.SHADE_LOCKED)
    }

    private fun getNotificationLoad(): Int {
        return if (headsUpManager.hasPinnedHeadsUp() && isNotifPresenterFullyCollapsed) {
            1
        } else {
            getActiveNotificationsCount()
        }
    }

    private fun getActiveNotificationsCount(): Int {
        return if (NotificationsLiveDataStoreRefactor.isEnabled) {
            activeNotificationsInteractor.allNotificationsCountValue
        } else {
            notificationsController?.getActiveNotificationsCount() ?: 0
        }
    }
}