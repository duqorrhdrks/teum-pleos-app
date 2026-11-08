package app.teum.loadaware;

import app.teum.loadaware.ILoadStateListener;

/**
 * 틈 부하 인지 API v1.
 * 바인드에는 app.teum.permission.READ_LOAD_STATE 가 필요하다.
 * submitMessage 에는 app.teum.permission.SUBMIT_MESSAGE 가 추가로 필요하다.
 */
interface ITeumLoadService {
    int getApiVersion();
    /** 현재 부하 상태. 아직 모르면 빈 Bundle. */
    Bundle getLoadState();
    void registerListener(ILoadStateListener listener);
    void unregisterListener(ILoadStateListener listener);
    /**
     * 알림 전달을 틈에 위임한다. 틈이 운전 부하에 맞춰 보류·일괄 전달한다.
     * @return LoadAwareContract.SUBMIT_* 결과 코드
     */
    int submitMessage(String sender, String text, boolean isCall);
}
