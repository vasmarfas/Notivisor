package com.vasmarfas.notivisor.phone.service;

// destroy() keeps Shizuku's reserved transaction id, so every method has to carry one explicitly.
interface IRemoteInput {
    void tap(float x, float y) = 1;
    void swipe(float fromX, float fromY, float toX, float toY, int durationMs) = 2;
    void key(int keyCode) = 3;
    void destroy() = 16777114;
}
