package org.hive2hive.core.test.processes.implementations.userprofiletask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;

import net.tomp2p.futures.FutureGet;
import net.tomp2p.peers.Number160;

import org.bouncycastle.crypto.DataLengthException;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.hive2hive.core.H2HConstants;
import org.hive2hive.core.H2HSession;
import org.hive2hive.core.exceptions.NoPeerConnectionException;
import org.hive2hive.core.network.NetworkManager;
import org.hive2hive.core.network.data.UserProfileManager;
import org.hive2hive.core.network.userprofiletask.UserProfileTask;
import org.hive2hive.core.processes.framework.RollbackReason;
import org.hive2hive.core.processes.framework.concretes.SequentialProcess;
import org.hive2hive.core.processes.framework.decorators.AsyncComponent;
import org.hive2hive.core.processes.framework.exceptions.InvalidProcessStateException;
import org.hive2hive.core.processes.framework.exceptions.ProcessExecutionException;
import org.hive2hive.core.processes.implementations.common.userprofiletask.GetUserProfileTaskStep;
import org.hive2hive.core.processes.implementations.common.userprofiletask.RemoveUserProfileTaskStep;
import org.hive2hive.core.processes.implementations.context.interfaces.IConsumeUserProfileTask;
import org.hive2hive.core.processes.implementations.context.interfaces.IProvideUserProfileTask;
import org.hive2hive.core.security.EncryptionUtil;
import org.hive2hive.core.security.UserCredentials;
import org.hive2hive.core.test.H2HJUnitTest;
import org.hive2hive.core.test.network.NetworkTestUtil;
import org.hive2hive.core.test.processes.util.TestProcessComponentListener;
import org.hive2hive.core.test.processes.util.UseCaseTestUtil;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

public class UserProfileTaskQueueTest extends H2HJUnitTest {

	private static List<NetworkManager> network;
	private static final int networkSize = 3;
	private Random random = new Random();

	@BeforeClass
	public static void initTest() throws Exception {
		testClass = UserProfileTaskQueueTest.class;
		beforeClass();
	}

	@Before
	public void setup() {
		network = NetworkTestUtil.createNetwork(networkSize);
	}

	@Test
	public void testPut() throws NoPeerConnectionException {
		String userId = NetworkTestUtil.randomString();
		TestUserProfileTask userProfileTask = new TestUserProfileTask();
		KeyPair key = EncryptionUtil.generateRSAKeyPair(H2HConstants.KEYLENGTH_USER_KEYS);
		NetworkManager node = network.get(random.nextInt(networkSize));

		TestPutUserProfileTaskStep putStep = new TestPutUserProfileTaskStep(userId, userProfileTask,
				key.getPublic(), node);
		UseCaseTestUtil.executeProcess(putStep);

		Number160 lKey = Number160.createHash(userId);
		Number160 dKey = Number160.createHash(H2HConstants.USER_PROFILE_TASK_DOMAIN);
		FutureGet futureGet = node.getDataManager().get(lKey, dKey, userProfileTask.getContentKey());
		futureGet.awaitUninterruptibly();

		assertNotNull(futureGet.getData());
	}

	// TODO: how to test this?
	@Ignore
	@Test
	public void testPutRollback() throws InvalidProcessStateException, NoPeerConnectionException, ProcessExecutionException {
		String userId = NetworkTestUtil.randomString();
		TestUserProfileTask userProfileTask = new TestUserProfileTask();
		KeyPair key = EncryptionUtil.generateRSAKeyPair(H2HConstants.KEYLENGTH_USER_KEYS);
		NetworkManager node = network.get(random.nextInt(networkSize));

		TestPutUserProfileTaskStep putStep = new TestPutUserProfileTaskStep(userId, userProfileTask,
				key.getPublic(), node);

		TestProcessComponentListener listener = new TestProcessComponentListener();
		AsyncComponent component = new AsyncComponent(putStep);
		component.attachListener(listener);

		// start and cancel immediately
		component.start();
		putStep.cancel(new RollbackReason(new ProcessExecutionException("Testing whether rollback works")));
		UseCaseTestUtil.waitTillFailed(listener, 10);

		Number160 lKey = Number160.createHash(userId);
		Number160 dKey = Number160.createHash(H2HConstants.USER_PROFILE_TASK_DOMAIN);

		FutureGet futureGet = node.getDataManager().get(lKey, dKey, userProfileTask.getContentKey());
		futureGet.awaitUninterruptibly();
		assertNull(futureGet.getData());
	}

	@Test
	public void testPutGet() {
		String userId = NetworkTestUtil.randomString();
		TestUserProfileTask userProfileTask = new TestUserProfileTask();
		KeyPair key = EncryptionUtil.generateRSAKeyPair(H2HConstants.KEYLENGTH_USER_KEYS);
		NetworkManager node = network.get(random.nextInt(networkSize));
		node.setSession(new H2HSession(key, new UserProfileManager(node, new UserCredentials(userId,
				"password", "pin")), null, null));

		SimpleGetUserProfileTaskContext context = new SimpleGetUserProfileTaskContext();

		SequentialProcess process = new SequentialProcess();
		process.add(new TestPutUserProfileTaskStep(userId, userProfileTask, key.getPublic(), node));
		process.add(new GetUserProfileTaskStep(context, node));

		UseCaseTestUtil.executeProcess(process);

		assertNotNull(context.consumeUserProfileTask());
		assertEquals(userProfileTask.getId(),
				((TestUserProfileTask) context.consumeUserProfileTask()).getId());
	}

	// TODO how to test this?
	@Ignore
	@Test
	public void testPutGetRollback() {
		String userId = NetworkTestUtil.randomString();
		TestUserProfileTask userProfileTask = new TestUserProfileTask();
		KeyPair key = EncryptionUtil.generateRSAKeyPair(H2HConstants.KEYLENGTH_USER_KEYS);
		NetworkManager node = network.get(random.nextInt(networkSize));
		node.setSession(new H2HSession(key, new UserProfileManager(node, new UserCredentials(userId,
				"password", "pin")), null, null));
		SimpleGetUserProfileTaskContext context = new SimpleGetUserProfileTaskContext();

		SequentialProcess process = new SequentialProcess();
		process.add(new TestPutUserProfileTaskStep(userId, userProfileTask, key.getPublic(), node));
		process.add(new GetUserProfileTaskStep(context, node));

		UseCaseTestUtil.executeProcess(process);
	}

	@Test
	public void testPutGetRemove() throws NoPeerConnectionException {
		String userId = NetworkTestUtil.randomString();
		TestUserProfileTask userProfileTask = new TestUserProfileTask();
		KeyPair key = EncryptionUtil.generateRSAKeyPair(H2HConstants.KEYLENGTH_USER_KEYS);
		NetworkManager node = network.get(random.nextInt(networkSize));
		node.setSession(new H2HSession(key, new UserProfileManager(node, new UserCredentials(userId,
				"password", "pin")), null, null));
		SimpleGetUserProfileTaskContext context = new SimpleGetUserProfileTaskContext();

		SequentialProcess process = new SequentialProcess();
		process.add(new TestPutUserProfileTaskStep(userId, userProfileTask, key.getPublic(), node));
		process.add(new GetUserProfileTaskStep(context, node));
		process.add(new RemoveUserProfileTaskStep(context, node));

		UseCaseTestUtil.executeProcess(process);

		Number160 lKey = Number160.createHash(userId);
		Number160 dKey = Number160.createHash(H2HConstants.USER_PROFILE_TASK_DOMAIN);
		FutureGet futureGet = node.getDataManager().get(lKey, dKey, userProfileTask.getContentKey());
		futureGet.awaitUninterruptibly();

		assertNull(futureGet.getData());
	}

	// TODO how to test this?
	@Test
	@Ignore
	public void testRemoveRollback() throws DataLengthException, InvalidKeyException, IllegalStateException,
			InvalidCipherTextException, IllegalBlockSizeException, BadPaddingException,
			ClassNotFoundException, IOException {
		String userId = NetworkTestUtil.randomString();
		TestUserProfileTask userProfileTask = new TestUserProfileTask();
		KeyPair key = EncryptionUtil.generateRSAKeyPair(H2HConstants.KEYLENGTH_USER_KEYS);
		NetworkManager node = network.get(random.nextInt(networkSize));
		node.setSession(new H2HSession(key, new UserProfileManager(node, new UserCredentials(userId,
				"password", "pin")), null, null));

		// IGetUserProfileTaskContext context = new SimpleGetUserProfileTaskContext();
		// HybridEncryptedContent encrypted = H2HEncryptionUtil.encryptHybrid(userProfileTask,
		// key.getPublic());
		// context.setEncryptedUserProfileTask(encrypted);
		// context.setUserProfileTask(userProfileTask);
		//
		// Number160 lKey = Number160.createHash(userId);
		// Number160 dKey = Number160.createHash(H2HConstants.USER_PROFILE_TASK_DOMAIN);
		// FuturePut futurePut = node.getDataManager().put(lKey, dKey, userProfileTask.getContentKey(),
		// userProfileTask, null);
		// futurePut.awaitUninterruptibly();
		//
		// RemoveUserProfileTaskStep removeStep = new RemoveUserProfileTaskStep(context, null);
		//
		// Process process = new Process(node) {
		// };
		// process.setNextStep(removeStep);
		// TestProcessListener listener = new TestProcessListener();
		// process.addListener(listener);
		// process.start();
		// ProcessTestUtil.waitTillSucceded(listener, 10);
		//
		// FutureGet futureGet = node.getDataManager().get(lKey, dKey, userProfileTask.getContentKey());
		// futureGet.awaitUninterruptibly();
		//
		// assertNull(futureGet.getData());
		//
		// listener.reset();
		// process.stop("On purpose triggered rollbacking for test.");
		//
		// ProcessTestUtil.waitTillFailed(listener, 10);
		//
		// futureGet = node.getDataManager().get(lKey, dKey, userProfileTask.getContentKey());
		// futureGet.awaitUninterruptibly();
		// assertNotNull(futureGet.getData());
		// TestUserProfileTask decrypted = (TestUserProfileTask) H2HEncryptionUtil.decryptHybrid(
		// (HybridEncryptedContent) futureGet.getData().object(), key.getPrivate());
		// assertEquals(userProfileTask.getId(), decrypted.getId());
	}

	@Test
	public void testCorrectOrder() throws DataLengthException, InvalidKeyException, IllegalStateException,
			InvalidCipherTextException, IllegalBlockSizeException, BadPaddingException, InterruptedException {
		String userId = NetworkTestUtil.randomString();
		NetworkManager node = network.get(random.nextInt(networkSize));
		KeyPair key = EncryptionUtil.generateRSAKeyPair(H2HConstants.KEYLENGTH_USER_KEYS);
		node.setSession(new H2HSession(key, new UserProfileManager(node, new UserCredentials(userId,
				"password", "pin")), null, null));

		// create some tasks
		List<TestUserProfileTask> tasks = new ArrayList<TestUserProfileTask>();
		for (int i = 0; i < 5; i++) {
			TestUserProfileTask task = new TestUserProfileTask();
			tasks.add(task);
			// to guarantee different time stamps
			Thread.sleep(10);
		}

		// shuffle tasks to change the order
		List<TestUserProfileTask> shuffledTasks = new ArrayList<TestUserProfileTask>(tasks);
		Collections.shuffle(shuffledTasks);
		for (TestUserProfileTask task : shuffledTasks) {
			TestPutUserProfileTaskStep putStep = new TestPutUserProfileTaskStep(userId, task,
					key.getPublic(), node);
			UseCaseTestUtil.executeProcess(putStep);
		}

		// fetch task from network, respectively the implicit queue
		List<TestUserProfileTask> downloadedTasks = new ArrayList<TestUserProfileTask>();
		SimpleGetUserProfileTaskContext context = new SimpleGetUserProfileTaskContext();
		while (true) {
			GetUserProfileTaskStep getStep = new GetUserProfileTaskStep(context, node);
			UseCaseTestUtil.executeProcess(getStep);
			if (context.consumeUserProfileTask() != null) {
				TestUserProfileTask task = (TestUserProfileTask) context.consumeUserProfileTask();
				downloadedTasks.add(task);
				// remove successfully get user profile tasks
				RemoveUserProfileTaskStep removeStep = new RemoveUserProfileTaskStep(context, node);
				UseCaseTestUtil.executeProcess(removeStep);
			} else {
				break;
			}
		}

		// order of fetched tasks should be like the initial one
		assertEquals(tasks.size(), downloadedTasks.size());
		for (int i = 0; i < tasks.size(); i++) {
			assertEquals(tasks.get(i).getId(), downloadedTasks.get(i).getId());
		}
	}

	@After
	public void shutdown() {
		NetworkTestUtil.shutdownNetwork(network);
	}

	@AfterClass
	public static void cleanAfterClass() {
		afterClass();
	}

	private class SimpleGetUserProfileTaskContext implements IProvideUserProfileTask, IConsumeUserProfileTask {

		private UserProfileTask userProfileTask;

		@Override
		public void provideUserProfileTask(UserProfileTask profileTask) {
			userProfileTask = profileTask;
		}

		@Override
		public UserProfileTask consumeUserProfileTask() {
			return userProfileTask;
		}
	};

}
