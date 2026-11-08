package app.teum.loadaware;

/** 부하 단계 변경 콜백. 키는 LoadAwareContract 참고. */
oneway interface ILoadStateListener {
    void onLoadStateChanged(in Bundle state);
}
